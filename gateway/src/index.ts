import { DurableObject } from 'cloudflare:workers';
import { createMcpHandler, McpServer } from '@modelcontextprotocol/server';
import { CfWorkerJsonSchemaValidator } from '@modelcontextprotocol/server/validators/cf-worker';
import * as z from 'zod';
import { authenticate, bearer, constantEqual, sha256, type Env } from './auth.js';
import { oauthFetch } from './oauth.js';
import type { Invitation, PhoneAuthorization } from './pairing.js';
import { invocationEnvelope, invocationSchema } from './invocation.js';

import {readCatalog, type Capability, type CatalogReply} from './catalog.js';
import {DiscoveryReads, type DiscoveryScope} from './discovery.js';

type SocketState = { epoch: string; ready: boolean; connectionProbe?: boolean; scopedDiscovery?: boolean; revocationReplay?: boolean };
type Pending = { epoch: string; agentId: string; resolve: (value: unknown) => void; reject: (reason: Error) => void; timer: ReturnType<typeof setTimeout> };
const encoder = new TextEncoder();
const json = (body: unknown, status = 200) => Response.json(body, { status, headers: { 'Cache-Control': 'no-store' } });

export class DeviceBridge extends DurableObject<Env> {
  private probe?: { epoch: string; id: string; result: Promise<boolean>; finish: (answered: boolean) => void };
  private readonly pending = new Map<string, Pending>();
  private readonly rate = new Map<string, { slot: number; count: number }>();

  private readonly scopeReads = new DiscoveryReads();

  private async discoveryScope(agent: string, agentToken: string): Promise<DiscoveryScope> {
    const credentialHash = await sha256(agentToken);
    const socket = await this.activeSocket();
    if (!socket) return {allowed: [], status: 'NO_SESSION'};
    const state = socket.deserializeAttachment() as SocketState;
    if (!state.scopedDiscovery) return {allowed: [], status: 'UNCONFIRMED'};
    return this.scopeReads.read(state.epoch, agent, credentialHash, id => {
      socket.send(JSON.stringify({type: 'discovery_scope', id, agentToken}));
    });
  }

  async fetch(request: Request): Promise<Response> {
    const path = new URL(request.url).pathname;
    if (path === '/register') {
      // Only the outer Worker can call this internal route after enrollment authentication.
      const body = await request.json() as { hash: string };
      const matches = await this.ctx.storage.transaction(async storage => {
        const existing = await storage.get<string>('credential');
        if (existing) return constantEqual(existing, body.hash);
        await storage.put('credential', body.hash);
        return true;
      });
      return matches ? json({ registered: true }) : json({ error: 'ALREADY_REGISTERED' }, 409);
    }
    if (path === '/socket') {
      const token = bearer(request);
      const expected = await this.ctx.storage.get<string>('credential');
      if (!token || !expected || !constantEqual(await sha256(token), expected)) return json({ error: 'UNAUTHORIZED_DEVICE' }, 401);
      if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') return json({ error: 'WEBSOCKET_REQUIRED' }, 426);
      const epoch = crypto.randomUUID();
      await this.ctx.storage.put('socketEpoch', epoch);
      this.probe?.finish(false);
      for (const p of this.pending.values()) p.reject(new Error('DEVICE_REPLACED_UNKNOWN_OUTCOME'));
      this.scopeReads.disconnect();
      for (const existing of this.ctx.getWebSockets('device')) existing.close(1000, 'Replaced by authenticated reconnect');
      const pair = new WebSocketPair();
      this.ctx.acceptWebSocket(pair[1], ['device']);
      pair[1].serializeAttachment({ epoch, ready: false } satisfies SocketState);
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    if (path === '/pairing/consume') {
      const { hash } = await request.json() as { hash: string };
      if (!/^[a-f0-9]{64}$/.test(hash)) return json({ error: 'INVALID_PAIRING' }, 400);
      const invitation = await this.ctx.storage.transaction(async storage => {
        const item = await storage.get<Invitation>('pair:' + hash);
        if (!item || item.expiresAt <= Date.now()) return undefined;
        if (await storage.get<boolean>('revoked:' + item.agentId)) return undefined;
        if ((await storage.get<string[]>('revokedAgents') ?? []).includes(item.agentId)) return undefined;
        await storage.delete('pair:' + hash);
        await storage.put({ ['used:' + hash]: item.expiresAt, ['lastPaired:' + item.agentId]: hash });
        return item;
      });
      if (!invitation) return json({ error: 'INVALID_PAIRING' }, 401);
      for (const socket of this.ctx.getWebSockets('device')) {
        if (socket.readyState !== 1) continue;
        try { socket.send(JSON.stringify({ type: 'pairing_consumed', agentId: invitation.agentId, hash })); }
        catch { /* The committed receipt can be retrieved after reconnection. */ }
      }
      return json(invitation);
    }
    if (path === '/pairing/check') {
      // Non-consuming validity check so a mistyped code does not spend the browser's consent.
      const { hash } = await request.json() as { hash: string };
      const item = /^[a-f0-9]{64}$/.test(hash) ? await this.ctx.storage.get<Invitation>('pair:' + hash) : undefined;
      const valid = !!item && item.expiresAt > Date.now() && !await this.revoked(item.agentId);
      return json({ valid }, valid ? 200 : 401);
    }
    const agent = new URL(request.url).searchParams.get('agent');
    if (agent && await this.revoked(agent)) return json({ error: 'AGENT_REVOKED', detail: 'The phone owner revoked this agent. Pair a new agent in SIConnect.' }, 403);
    if (path === '/session-status' && agent) return this.sessionStatus();
    if (path === '/grant-status' && agent) return json({ active: true });
    if (path === '/discovery-scope' && agent && request.method === 'POST') {
      const {agentToken} = await request.json() as {agentToken: string};
      return json(await this.discoveryScope(agent, agentToken));
    }
    if (path === '/catalog') {
      // Only the internal MCP caller can ask for metadata alone. The bearer endpoint always gets its scoped snapshot.
      const known = request.method === 'GET' ? request.headers.get('X-SIConnect-Catalog-Revision') : null;
      const catalog = await readCatalog(this.ctx.storage, known);
      if ('unchanged' in catalog) return json(catalog);
      let scopeStatus: DiscoveryScope['status'] | undefined;
      let capabilities = catalog.capabilities;
      if (catalog.scopedDiscovery && request.method === 'POST') {
        if (!agent) return json({error: 'UNAUTHORIZED'}, 401);
        const {agentToken} = await request.json() as {agentToken: string};
        const scope = await this.discoveryScope(agent, agentToken);
        scopeStatus = scope.status;
        const allowed = new Set(scope.allowed);
        capabilities = capabilities.filter(cap => allowed.has(cap.descriptor.id));
      }
      return json({protocol: 2, online: !!await this.activeSocket(), ...catalog, scopeStatus, capabilities});
    }
    if (path === '/invoke') {
      const body = await request.json() as { agentId: string; agentToken: string; transportExpiresAtMs?: number; request: Record<string, unknown> };
      if (await this.revoked(body.agentId)) return json({ error: 'AGENT_REVOKED', executionDispatched: false }, 403);
      const slot = Math.floor(Date.now() / 60_000);
      const rate = this.rate.get(body.agentId);
      if (rate?.slot === slot && rate.count >= 60) return json({ error: 'RATE_LIMITED' }, 429);
      this.rate.set(body.agentId, { slot, count: rate?.slot === slot ? rate.count + 1 : 1 });
      if (this.rate.size > 1000) this.rate.clear();
      const socket = await this.activeSocket();
      if (!socket) return json({ error: 'DEVICE_OFFLINE', observedAtMs: Date.now(), failureLayer: 'TRANSPORT', executionDispatched: false,
        backendAvailability: 'UNKNOWN',
        detail: 'No acknowledged phone connection. This attempt was not forwarded; the phone backend state was not measured. Preserve any original request identity while restoring the existing connection.' }, 503);
      // Per-agent cap first: one client's abandoned or slow calls must not starve every other agent.
      let ownPending = 0;
      for (const p of this.pending.values()) if (p.agentId === body.agentId) ownPending++;
      if (ownPending >= 8) return json({ error: 'AGENT_BUSY', executionDispatched: false, detail: 'This agent already has 8 calls in flight on the phone. Wait for them or read their request IDs.' }, 429);
      if (this.pending.size >= 32) return json({ error: 'DEVICE_BUSY', executionDispatched: false }, 429);
      const epoch = (socket.deserializeAttachment() as SocketState).epoch;
      const id = String(body.request.requestId);
      if (this.pending.has(id)) return json({ error: 'REQUEST_IN_FLIGHT', requestId: id,
        recovery: { tool: 'requests_get', arguments: { requestId: id }, doNotResubmitMutation: true } }, 409);
      const expiry = Number(body.transportExpiresAtMs ?? body.request.expiresAtMs);
      if (!Number.isFinite(expiry) || expiry <= Date.now()) return json({ error: 'EXPIRED' }, 408);
      // Transport cancellation is not execution cancellation: the action may already
      // have committed. Only the authenticated requests.cancel tool asks the device
      // to stop work. A client must reconcile the original record after losing HTTP.
      let dispatched = false;
      const aborted = () => {
        this.pending.get(id)?.reject(new Error(dispatched ? 'CLIENT_DISCONNECTED_UNKNOWN_OUTCOME' : 'CLIENT_DISCONNECTED_NOT_DISPATCHED'));
      };
      try {
        const result = await new Promise((resolve, reject) => {
          const timer = setTimeout(() => reject(new Error('GATEWAY_TIMEOUT_UNKNOWN_OUTCOME')), Math.min(expiry - Date.now(), 130_000));
          this.pending.set(id, { epoch, agentId: body.agentId, resolve, reject, timer });
          request.signal.addEventListener('abort', aborted, { once: true });
          if (request.signal.aborted) { aborted(); return; }
          const envelope = JSON.stringify({ type: 'invoke', id, agentToken: body.agentToken, request: body.request });
          if (encoder.encode(envelope).byteLength > 131072) { reject(new Error('REQUEST_TOO_LARGE_NOT_DISPATCHED')); return; }
          socket.send(envelope);
          dispatched = true;
        });
        return json(result);
      } catch (error) { return json({ error: (error as Error).message, requestId: id, recovery: { tool: 'requests_get', arguments: { requestId: id }, doNotResubmitMutation: true } }, 504); }
      finally {
        const entry = this.pending.get(id);
        if (entry) clearTimeout(entry.timer);
        this.pending.delete(id);
        request.signal.removeEventListener('abort', aborted);
      }
    }
    return json({ error: 'NOT_FOUND' }, 404);
  }
  async webSocketMessage(socket: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== 'string' || encoder.encode(message).byteLength > 1_048_576) { this.closeDevice(socket, 1009, 'Envelope too large'); return; }
    try {
      const attachment = socket.deserializeAttachment() as SocketState | null;
      if (!attachment?.epoch || attachment.epoch !== await this.ctx.storage.get<string>('socketEpoch')) { this.closeDevice(socket, 1008, 'Stale device session'); return; }
      const body = JSON.parse(message);
      if (body.type !== 'hello' && !attachment.ready) throw new Error('Device hello required');
      if (body.type === 'hello') {
        if (!Array.isArray(body.capabilities) || body.capabilities.length > 128) throw new Error('Invalid catalog');
        for (const cap of body.capabilities as Capability[]) {
          if (!/^[a-z][a-z0-9]*(\.[a-z][a-z0-9_]*)+$/.test(cap.descriptor.id)) throw new Error('Invalid capability');
        }
        const revoked = body.revokedAgents;
        if (revoked !== undefined && (!Array.isArray(revoked) || revoked.length > 500 ||
            !revoked.every(id => typeof id === 'string' && /^[A-Za-z0-9_-]{1,100}$/.test(id)))) throw new Error('Invalid revocation list');
        const revision = await sha256(JSON.stringify(body.capabilities));
        await this.ctx.storage.transaction(async storage => {
          if (attachment.epoch !== await storage.get('socketEpoch')) throw new Error('Replaced device session');
          if (revoked !== undefined) {
            const incoming = new Set<string>(revoked);
            const previous = await storage.get<string[]>('revokedAgents') ?? [];
            for (const id of previous) {
              if (!incoming.has(id)) await storage.put('revoked:' + id, true);
            }
            if (incoming.size !== previous.length || previous.some(id => !incoming.has(id)))
              await storage.put('revokedAgents', [...incoming]);
          }
          const scoped = body.scopedDiscovery === true;
          if (scoped !== (await storage.get('scopedDiscovery') === true)) await storage.put('scopedDiscovery', scoped);
          if (revision !== await storage.get('catalogRevision'))
            await storage.put({ capabilities: body.capabilities, catalogRevision: revision });
        });
        socket.serializeAttachment({ ...attachment, ready: true, connectionProbe: body.connectionProbe === true, scopedDiscovery: body.scopedDiscovery === true, revocationReplay: body.revocationReplay === true } satisfies SocketState);
        socket.send(JSON.stringify({ type: 'hello_ack', protocol: 2, revocationReplay: body.revocationReplay === true }));
      } else if (body.type === 'revocations' && attachment.revocationReplay) {
        const ids: unknown = body.agents;
        if (typeof body.id !== 'string' || body.id.length !== 36 || !Array.isArray(ids) || ids.length < 1 ||
            ids.length > 64 || !ids.every(id => typeof id === 'string' && /^[A-Za-z0-9_-]{1,100}$/.test(id))) throw new Error('Invalid revocations');
        const keys = ids.map(id => 'revoked:' + id);
        await this.ctx.storage.transaction(async storage => {
          if (attachment.epoch !== await storage.get('socketEpoch')) throw new Error('Replaced session');
          const saved = await storage.get(keys);
          const missing = keys.filter(key => !saved.has(key));
          if (missing.length) await storage.put(Object.fromEntries(missing.map(key => [key, true])));
        });
        socket.send(JSON.stringify({type: 'revocations_saved', id: body.id}));
      } else if (body.type === 'discovery_scope_result' && attachment.scopedDiscovery) {
        if (Array.isArray(body.allowed) && body.allowed.length <= 128 &&
            body.allowed.every((id: unknown) => typeof id === 'string')) {
          this.scopeReads.answer(attachment.epoch, body.id, body.allowed);
        }
      } else if (body.type === 'connection_probe_ack') {
        const probe = this.probe;
        if (probe && probe.epoch === attachment.epoch && probe.id === body.id) probe.finish(true);
      } else if (body.type === 'pairing_check') {
        const { agentId, hash } = body;
        if (typeof agentId !== 'string' || !/^[A-Za-z0-9_-]{1,100}$/.test(agentId) ||
            typeof hash !== 'string' || !/^[a-f0-9]{64}$/.test(hash)) throw new Error('Invalid receipt query');
        const keys = await this.ctx.storage.get<string | number | Invitation>(['lastPaired:' + agentId, 'used:' + hash, 'pair:' + hash]);
        const item = keys.get('pair:' + hash);
        const consumed = keys.get('lastPaired:' + agentId) === hash || keys.has('used:' + hash);
        const saved = typeof item === 'object' && item.agentId === agentId && item.expiresAt > Date.now();
        socket.send(JSON.stringify({ type: 'pairing_status', agentId, hash, status: consumed ? 'consumed' : saved ? 'saved' : 'unknown' }));
      } else if (body.type === 'pairing') {
        const item = body.invitation as Invitation;
        if (!item || !/^[a-f0-9]{64}$/.test(item.hash) || !/^[A-Za-z0-9_-]{1,100}$/.test(item.agentId) ||
            !Number.isSafeInteger(item.expiresAt) || item.expiresAt <= Date.now() || item.expiresAt > Date.now() + 610_000 ||
            typeof item.sealed !== 'string' || !/^[A-Za-z0-9_-]{60,2048}$/.test(item.sealed)) throw new Error('Invalid invitation');
        const used = await this.ctx.storage.list<number>({ prefix: 'used:' });
        for (const [key, expiry] of used) if (expiry <= Date.now()) await this.ctx.storage.delete(key);
        if ((used.get('used:' + item.hash) ?? 0) > Date.now()) {
          socket.send(JSON.stringify({ type: 'pairing_consumed', agentId: item.agentId, hash: item.hash }));
          return;
        }
        const invitations = await this.ctx.storage.list<Invitation>({ prefix: 'pair:' });
        for (const [key, value] of invitations) if (value.expiresAt <= Date.now()) await this.ctx.storage.delete(key);
        if ([...invitations.values()].filter(v => v.expiresAt > Date.now()).length >= 32 && !invitations.has('pair:' + item.hash)) throw new Error('Pairing limit');
        await this.ctx.storage.put('pair:' + item.hash, item);
        socket.send(JSON.stringify({ type: 'pairing_saved', hash: item.hash }));
      } else if (body.type === 'result' || body.type === 'error') {
        const pending = this.pending.get(body.id);
        if (pending?.epoch === attachment.epoch) {
          if (body.type === 'result' && body.record?.requestId !== body.id) throw new Error('Result correlation mismatch');
          if (body.type === 'error' && body.requestId && body.requestId !== body.id) throw new Error('Error correlation mismatch');
          pending.resolve(body.type === 'result' ? body.record : {
            error: typeof body.code === 'string' ? body.code : 'DEVICE_ERROR', requestId: body.id,
            ...(typeof body.executionState === 'string' ? { executionState: body.executionState } : {}),
            recovery: body.recovery ?? { tool: 'requests_get', arguments: { requestId: body.id }, doNotResubmitMutation: true }
          });
        }
      }
    } catch { this.closeDevice(socket, 1008, 'Invalid device envelope'); }
  }
  /** Only explicit status calls contact the phone. Concurrent callers share one bounded check. */
  private async sessionStatus(): Promise<Response> {
    const socket = await this.activeSocket();
    if (!socket) return json({phoneSessionAcknowledged: false, phoneReachability: 'NO_SESSION'});
    const state = socket.deserializeAttachment() as SocketState;
    if (!state.connectionProbe) return json({phoneSessionAcknowledged: true, phoneReachability: 'UNSUPPORTED'});
    let probe = this.probe;
    if (!probe || probe.epoch !== state.epoch) {
      const id = crypto.randomUUID();
      let resolve!: (answered: boolean) => void;
      const result = new Promise<boolean>(done => { resolve = done; });
      const finish = (answered: boolean) => {
        clearTimeout(timer);
        if (this.probe?.id === id) this.probe = undefined;
        resolve(answered);
      };
      const timer = setTimeout(() => finish(false), 4000);
      this.probe = probe = {epoch: state.epoch, id, result, finish};
      try { socket.send(JSON.stringify({type: 'connection_probe', id})); }
      catch { finish(false); }
    }
    if (await probe.result) return json({phoneSessionAcknowledged: true, phoneReachability: 'RESPONDED'});
    const online = !!await this.activeSocket();
    return json({phoneSessionAcknowledged: online, phoneReachability: online ? 'UNCONFIRMED' : 'NO_SESSION'});
  }

  /** Server-initiated close: fail this session's pending calls now rather than at their 130s timer. */
  private closeDevice(socket: WebSocket, code: number, reason: string): void {
    this.endSession(socket);
    socket.close(code, reason);
  }
  private async revoked(agentId: string): Promise<boolean> {
    const entries = await this.ctx.storage.get<string[] | boolean>(['revokedAgents', 'revoked:' + agentId]);
    const recent = entries.get('revokedAgents');
    return entries.has('revoked:' + agentId) || (Array.isArray(recent) && recent.includes(agentId));
  }
  private async activeSocket(): Promise<WebSocket | undefined> {
    const epoch = await this.ctx.storage.get<string>('socketEpoch');
    return this.ctx.getWebSockets('device').find(socket => {
      const state = socket.deserializeAttachment() as SocketState | null;
      // Rolling upgrades retain hibernated sockets. Older attachments must reconnect
      // through authentication/hello instead of appearing online indefinitely.
      if(!state?.epoch && socket.readyState===1) socket.close(1012,'Reconnect to upgrade device session');
      return socket.readyState === 1 && state?.ready && state.epoch === epoch;
    });
  }
  private endSession(socket: WebSocket): void {
    const epoch = (socket.deserializeAttachment() as SocketState | null)?.epoch;
    if (epoch) this.scopeReads.disconnect(epoch);
    if (this.probe && this.probe.epoch === epoch) this.probe.finish(false);
    for (const p of this.pending.values()) if (p.epoch === epoch) p.reject(new Error('DEVICE_DISCONNECTED_UNKNOWN_OUTCOME'));
  }
  webSocketClose(socket: WebSocket): void {
    this.endSession(socket);
    if (socket.readyState !== WebSocket.CLOSED) socket.close(1000, 'Device disconnected');
  }
  webSocketError(socket: WebSocket): void { this.closeDevice(socket, 1011, 'Transport failure'); }

}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    try {
      const url = new URL(request.url);
      if (url.pathname === '/health') return json({ service: 'SIConnect', authentication: ['oauth', 'private-bearer'], oauth: true });
      const device = /^\/(device|register)\/([A-Za-z0-9_-]{1,100})$/.exec(url.pathname);
      if (device) {
        const bridge = env.DEVICES.get(env.DEVICES.idFromName(device[2]));
        if (device[1] === 'register') {
          const token = bearer(request);
          if (!token || !env.DEVICE_ENROLL_TOKEN || !constantEqual(await sha256(token), await sha256(env.DEVICE_ENROLL_TOKEN))) return json({ error: 'UNAUTHORIZED' }, 401);
          if (request.method !== 'POST') return json({ error: 'METHOD_NOT_ALLOWED' }, 405);
          const body = await request.json() as { deviceToken: string };
          if (!/^[A-Za-z0-9_-]{20,300}$/.test(body.deviceToken)) return json({ error: 'INVALID_CREDENTIAL' }, 400);
          return bridge.fetch(new Request('https://internal/register', { method: 'POST', body: JSON.stringify({ hash: await sha256(body.deviceToken) }) }));
        }
        return bridge.fetch(new Request('https://internal/socket', request));
      }
      const principal = ['/mcp','/connection'].includes(url.pathname) ? await authenticate(request, env) : null;
      if(principal && url.pathname==='/connection') return env.DEVICES.get(env.DEVICES.idFromName(principal.deviceId)).fetch(new Request('https://internal/catalog?agent='+encodeURIComponent(principal.id), {method:'POST', body:JSON.stringify({agentToken: bearer(request)})}));
      if (!principal) return oauthFetch(request, env, ctx);
      const token = bearer(request)!;
      return serveMcp(request, env, { agentId: principal.id, deviceId: principal.deviceId, agentToken: token });
    } catch (error) {
      // Only the error class: messages may echo request content.
      console.error('gateway request failed', (error as Error)?.name ?? 'Error');
      return json({ error: 'INVALID_REQUEST' }, 400);
    }
  }
} satisfies ExportedHandler<Env>;

type ToolListing = {
  name: string; title: string; description: string; inputSchema: {type: 'object'};
  annotations: {readOnlyHint: boolean; destructiveHint: boolean};
  _meta: {'dev.siconnect/availability': string};
};
type CompiledTool = {id: string; inputSchema: z.ZodType; listing: ToolListing};
type CompiledCatalog = {revision: string | null; count: number; tools: CompiledTool[]};
/** Most recent compiled schema per device. Scope decisions are never stored here. */
const compiledCatalogs = new Map<string, CompiledCatalog>();
function compileTools(capabilities: Capability[]): CompiledTool[] {
  // One malformed or colliding phone descriptor must not take down the whole MCP endpoint.
  const tools: CompiledTool[] = [];
  const names = new Set(['siconnect_connection']);
  for (const cap of capabilities) {
    const name = cap.descriptor.id.replaceAll('.', '_');
    if (names.has(name)) { console.warn('skipping colliding tool name', name); continue; }
    try {
      const schema = invocationSchema(cap.descriptor.inputSchema);
      if (schema.type !== 'object') throw new Error('Object schema required');
      const listing: ToolListing = {
        name, title: cap.descriptor.title,
        description: cap.descriptor.description + ' Last reported phone availability: ' + cap.availability + '.',
        inputSchema: {...schema, type: 'object'},
        annotations: {readOnlyHint: cap.descriptor.risk === 'READ_ONLY', destructiveHint: ['HIGH_IMPACT', 'CRITICAL'].includes(cap.descriptor.risk)},
        _meta: {'dev.siconnect/availability': cap.availability}
      };
      tools.push({id: cap.descriptor.id, inputSchema: z.fromJSONSchema(schema), listing});
      names.add(name);
    }
    catch (error) { console.warn('skipping tool with unsupported schema', name, (error as Error)?.name ?? 'Error'); }
  }
  return tools;
}

export async function serveMcp(request: Request, env: Env, authorization: PhoneAuthorization): Promise<Response> {
      const bridge = env.DEVICES.get(env.DEVICES.idFromName(authorization.deviceId));
      const catalogUrl = 'https://internal/catalog?agent=' + encodeURIComponent(authorization.agentId);
      const cached = compiledCatalogs.get(authorization.deviceId);
      const catalogResponse = await bridge.fetch(catalogUrl, cached?.revision
        ? {headers: {'X-SIConnect-Catalog-Revision': cached.revision}} : undefined);
      if (!catalogResponse.ok) return catalogResponse;
      const catalog = await catalogResponse.json() as CatalogReply;
      let compiled: CompiledCatalog;
      if ('unchanged' in catalog) {
        if (!cached || cached.revision !== catalog.revision) return json({error: 'CATALOG_REVISION_MISMATCH'}, 502);
        compiled = cached;
      } else {
        compiled = {revision: catalog.revision, count: catalog.capabilities.length, tools: compileTools(catalog.capabilities)};
        if (compiled.revision && compiledCatalogs.get(authorization.deviceId) === cached) {
          if (!cached && compiledCatalogs.size >= 16) compiledCatalogs.delete(compiledCatalogs.keys().next().value!);
          compiledCatalogs.set(authorization.deviceId, compiled);
        }
      }
      const tools = compiled.tools;
      const handler = createMcpHandler(() => {
        const server = new McpServer({ name: 'SIConnect', version: '0.2.0' }, { jsonSchemaValidator: new CfWorkerJsonSchemaValidator() });
        for (const {id, listing, inputSchema} of tools) {
          server.registerTool(listing.name, {title: listing.title, description: listing.description,
            annotations: listing.annotations, _meta: listing._meta, inputSchema}, async (args, context) => {
            const envelope = await invocationEnvelope(authorization, id, args);
            const response = await bridge.fetch(new Request('https://internal/invoke', {
              // The SDK signal alone does not fire on client disconnect; the platform request signal does (enable_request_signal).
              method: 'POST', signal: AbortSignal.any([context.mcpReq.signal, request.signal]),
              body: JSON.stringify(envelope)
            }));
            const record = await response.json() as Record<string, unknown>;
            if (record.state === 'AWAITING_APPROVAL') record.continuation = { tool: 'requests_get', arguments: { requestId: record.requestId }, doNotResubmitMutation: true, approvalExpiresAtMs: record.approvalExpiresAtMs };
            return { content: [{ type: 'text', text: JSON.stringify(record) }], structuredContent: record,
              isError: !response.ok || (record.state !== 'SUCCEEDED' && record.state !== 'AWAITING_APPROVAL') };
          });
        }
        const connection = {
          title: 'Fresh SIConnect gateway connection status',
          description: 'Check whether the phone answers over its existing gateway connection. A RESPONDED result confirms a round trip. UNCONFIRMED means a session exists but a reply was not observed. Older phones report UNSUPPORTED. This read-only check runs no device action.',
          annotations: { readOnlyHint: true, destructiveHint: false }
        };
        server.registerTool('siconnect_connection', {...connection, inputSchema: z.object({})}, async () => {
          const response = await bridge.fetch('https://internal/session-status?agent=' + encodeURIComponent(authorization.agentId));
          const fresh = await response.json() as Record<string, unknown>;
          if (!response.ok) return {content: [{type: 'text', text: JSON.stringify(fresh)}], structuredContent: fresh, isError: true};
          const guidance: Record<string, string> = {
            RESPONDED: 'The phone answered this check. Individual tools still depend on their permissions and backend availability.',
            UNSUPPORTED: 'This phone build reports its handshake only. Update SIConnect or use an authorized read such as device_battery to check reachability.',
            UNCONFIRMED: 'The gateway has an acknowledged session but could not confirm a phone reply. Retry the status check. Keep original request IDs for any pending work.',
            NO_SESSION: 'Wait for the phone to reconnect to its existing gateway, then check again.',
          };
          const status = { ...fresh, observedAtMs: Date.now(), nativeCatalogTools: compiled.count,
            executionDispatched: false, observationScope: 'GATEWAY_TO_PHONE_SESSION', backendAvailability: 'NOT_MEASURED',
            guidance: guidance[String(fresh.phoneReachability)] };
          return { content: [{ type: 'text', text: JSON.stringify(status) }], structuredContent: status, isError: false };
        });
        if (catalog.scopedDiscovery) {
          server.server.setRequestHandler('tools/list', async () => {
            const response = await bridge.fetch(new Request('https://internal/discovery-scope?agent=' + encodeURIComponent(authorization.agentId), {
              method: 'POST', body: JSON.stringify({agentToken: authorization.agentToken})
            }));
            if (!response.ok) throw new Error('Agent discovery unavailable');
            const scope = await response.json() as DiscoveryScope;
            const permitted = new Set(scope.allowed);
            const scoped = tools.filter(tool => permitted.has(tool.id)).map(tool => tool.listing);
            return {_meta: {'dev.siconnect/scopeStatus': scope.status}, tools: [...scoped,
              {name: 'siconnect_connection', ...connection, inputSchema: {type: 'object' as const, properties: {}}}]};
          });
        }
        return server;
      }, { legacy: 'stateless', responseMode: 'json', maxRequestBodySize: 131072 });
      try { return await handler.fetch(request); } finally { await handler.close(); }
}
