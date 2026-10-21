import { DurableObject } from 'cloudflare:workers';
import { createMcpHandler, McpServer } from '@modelcontextprotocol/server';
import { CfWorkerJsonSchemaValidator } from '@modelcontextprotocol/server/validators/cf-worker';
import * as z from 'zod';
import { authenticate, bearer, constantEqual, sha256, type Env } from './auth.js';
import { oauthFetch } from './oauth.js';
import type { Invitation, PhoneAuthorization } from './pairing.js';

type Capability = { descriptor: { id: string; version: number; title: string; description: string; inputSchema: Record<string, unknown>; risk: string }; availability: string; detail?: string };
type SocketState = { epoch: string; ready: boolean };
type Pending = { epoch: string; resolve: (value: unknown) => void; reject: (reason: Error) => void; timer: ReturnType<typeof setTimeout> };
const encoder = new TextEncoder();
const json = (body: unknown, status = 200) => Response.json(body, { status, headers: { 'Cache-Control': 'no-store' } });

export class DeviceBridge extends DurableObject<Env> {
  private readonly pending = new Map<string, Pending>();
  private readonly rate = new Map<string, { slot: number; count: number }>();

  async fetch(request: Request): Promise<Response> {
    const path = new URL(request.url).pathname;
    if (path === '/register') {
      // Only the outer Worker can call this internal route after enrollment authentication.
      const body = await request.json() as { hash: string };
      if (await this.ctx.storage.get('credential')) return json({ error: 'ALREADY_REGISTERED' }, 409);
      await this.ctx.storage.put('credential', body.hash);
      return json({ registered: true });
    }
    if (path === '/socket') {
      const token = bearer(request);
      const expected = await this.ctx.storage.get<string>('credential');
      if (!token || !expected || !constantEqual(await sha256(token), expected)) return json({ error: 'UNAUTHORIZED_DEVICE' }, 401);
      if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') return json({ error: 'WEBSOCKET_REQUIRED' }, 426);
      const epoch = crypto.randomUUID();
      await this.ctx.storage.put('socketEpoch', epoch);
      for (const p of this.pending.values()) p.reject(new Error('DEVICE_REPLACED_UNKNOWN_OUTCOME'));
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
        if (item) { await storage.delete('pair:' + hash); await storage.put('used:' + hash, item.expiresAt); }
        return item;
      });
      if (!invitation || invitation.expiresAt <= Date.now()) return json({ error: 'INVALID_PAIRING' }, 401);
      for (const socket of this.ctx.getWebSockets('device')) if (socket.readyState === 1) socket.send(JSON.stringify({ type: 'pairing_consumed', agentId: invitation.agentId }));
      return json(invitation);
    }
    if (path === '/catalog') return json({ protocol: 2, online: !!await this.activeSocket(), capabilities: await this.ctx.storage.get('capabilities') ?? [] });
    if (path === '/invoke') {
      const body = await request.json() as { agentId: string; agentToken: string; request: Record<string, unknown> };
      const slot = Math.floor(Date.now() / 60_000);
      const rate = this.rate.get(body.agentId);
      if (rate?.slot === slot && rate.count >= 60) return json({ error: 'RATE_LIMITED' }, 429);
      this.rate.set(body.agentId, { slot, count: rate?.slot === slot ? rate.count + 1 : 1 });
      if (this.rate.size > 1000) this.rate.clear();
      const socket = await this.activeSocket();
      if (!socket) return json({ error: 'DEVICE_OFFLINE' }, 503);
      if (this.pending.size >= 16) return json({ error: 'DEVICE_BUSY' }, 429);
      const epoch = (socket.deserializeAttachment() as SocketState).epoch;
      const id = String(body.request.requestId);
      if (this.pending.has(id)) return json({ error: 'REQUEST_IN_FLIGHT' }, 409);
      const expiry = Number(body.request.expiresAtMs);
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
          this.pending.set(id, { epoch, resolve, reject, timer });
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
    if (typeof message !== 'string' || encoder.encode(message).byteLength > 1_048_576) { socket.close(1009, 'Envelope too large'); return; }
    try {
      const attachment = socket.deserializeAttachment() as SocketState | null;
      if (!attachment?.epoch || attachment.epoch !== await this.ctx.storage.get<string>('socketEpoch')) { socket.close(1008, 'Stale device session'); return; }
      const body = JSON.parse(message);
      if (body.type !== 'hello' && !attachment.ready) throw new Error('Device hello required');
      if (body.type === 'hello') {
        if (!Array.isArray(body.capabilities) || body.capabilities.length > 128) throw new Error('Invalid catalog');
        for (const cap of body.capabilities as Capability[]) {
          if (!/^[a-z][a-z0-9]*(\.[a-z][a-z0-9_]*)+$/.test(cap.descriptor.id)) throw new Error('Invalid capability');
        }
        await this.ctx.storage.put('capabilities', body.capabilities);
        socket.serializeAttachment({ ...attachment, ready: true } satisfies SocketState);
        socket.send(JSON.stringify({ type: 'hello_ack', protocol: 2 }));
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
    } catch { socket.close(1008, 'Invalid device envelope'); }
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
  webSocketClose(socket: WebSocket, code: number, reason: string, wasClean: boolean): void {
    const epoch = (socket.deserializeAttachment() as SocketState | null)?.epoch;
    for (const p of this.pending.values()) if (p.epoch === epoch) p.reject(new Error('DEVICE_DISCONNECTED_UNKNOWN_OUTCOME'));
  }
  webSocketError(socket: WebSocket): void { this.webSocketClose(socket, 1011, 'Transport failure', false); socket.close(1011, 'Transport failure'); }

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
      if(principal && url.pathname==='/connection') return env.DEVICES.get(env.DEVICES.idFromName(principal.deviceId)).fetch('https://internal/catalog');
      if (!principal) return oauthFetch(request, env, ctx);
      const token = bearer(request)!;
      return serveMcp(request, env, { agentId: principal.id, deviceId: principal.deviceId, agentToken: token });
    } catch { return json({ error: 'INVALID_REQUEST' }, 400); }
  }
} satisfies ExportedHandler<Env>;

export async function serveMcp(request: Request, env: Env, authorization: PhoneAuthorization): Promise<Response> {
      const bridge = env.DEVICES.get(env.DEVICES.idFromName(authorization.deviceId));
      const catalog = await (await bridge.fetch('https://internal/catalog')).json() as { online: boolean; capabilities: Capability[] };
      const handler = createMcpHandler(() => {
        const server = new McpServer({ name: 'SIConnect', version: '0.2.0' }, { jsonSchemaValidator: new CfWorkerJsonSchemaValidator() });
        for (const cap of catalog.capabilities) {
          const descriptor = cap.descriptor;
          server.registerTool(descriptor.id.replaceAll('.', '_'), {
            title: descriptor.title, description: descriptor.description,
            inputSchema: z.fromJSONSchema(descriptor.inputSchema),
            annotations: { readOnlyHint: descriptor.risk === 'READ_ONLY', destructiveHint: ['HIGH_IMPACT', 'CRITICAL'].includes(descriptor.risk) }
          }, async (args, context) => {
            const requestId = crypto.randomUUID();
            const response = await bridge.fetch(new Request('https://internal/invoke', {
              method: 'POST', signal: context.mcpReq.signal,
              body: JSON.stringify({ agentId: authorization.agentId, agentToken: authorization.agentToken, request: {
                requestId, traceId: requestId, capabilityId: descriptor.id, arguments: args, expiresAtMs: Date.now() + 15 * 60_000
              } })
            }));
            const record = await response.json() as Record<string, unknown>;
            if (record.state === 'AWAITING_APPROVAL') record.continuation = { tool: 'requests_get', arguments: { requestId: record.requestId }, doNotResubmitMutation: true, approvalExpiresAtMs: record.approvalExpiresAtMs };
            return { content: [{ type: 'text', text: JSON.stringify(record) }], structuredContent: record,
              isError: !response.ok || (record.state !== 'SUCCEEDED' && record.state !== 'AWAITING_APPROVAL') };
          });
        }
        return server;
      }, { legacy: 'stateless', responseMode: 'json', maxRequestBodySize: 131072 });
      try { return await handler.fetch(request); } finally { await handler.close(); }
}
