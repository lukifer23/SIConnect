import { DurableObject } from 'cloudflare:workers';
import { createMcpHandler, McpServer } from '@modelcontextprotocol/server';
import { CfWorkerJsonSchemaValidator } from '@modelcontextprotocol/server/validators/cf-worker';
import * as z from 'zod';
import { authenticate, bearer, constantEqual, sha256, type Env } from './auth.js';

type Capability = { descriptor: { id: string; version: number; title: string; description: string; inputSchema: Record<string, unknown>; risk: string }; availability: string; detail?: string };
type Pending = { resolve: (value: unknown) => void; reject: (reason: Error) => void; timer: ReturnType<typeof setTimeout> };
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
      for (const existing of this.ctx.getWebSockets('device')) existing.close(1000, 'Replaced by authenticated reconnect');
      const pair = new WebSocketPair();
      this.ctx.acceptWebSocket(pair[1], ['device']);
      pair[1].serializeAttachment({ onlineAt: Date.now() });
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    if (path === '/catalog') return json({ online: this.ctx.getWebSockets('device').some(s => s.readyState === 1), capabilities: await this.ctx.storage.get('capabilities') ?? [] });
    if (path === '/invoke') {
      const body = await request.json() as { agentId: string; agentToken: string; request: Record<string, unknown> };
      const slot = Math.floor(Date.now() / 60_000);
      const rate = this.rate.get(body.agentId);
      if (rate?.slot === slot && rate.count >= 60) return json({ error: 'RATE_LIMITED' }, 429);
      this.rate.set(body.agentId, { slot, count: rate?.slot === slot ? rate.count + 1 : 1 });
      if (this.rate.size > 1000) this.rate.clear();
      const socket = this.ctx.getWebSockets('device').find(s => s.readyState === 1);
      if (!socket) return json({ error: 'DEVICE_OFFLINE' }, 503);
      if (this.pending.size >= 16) return json({ error: 'DEVICE_BUSY' }, 429);
      const id = String(body.request.requestId);
      if (this.pending.has(id)) return json({ error: 'REQUEST_IN_FLIGHT' }, 409);
      const expiry = Number(body.request.expiresAtMs);
      if (!Number.isFinite(expiry) || expiry <= Date.now()) return json({ error: 'EXPIRED' }, 408);
      const aborted = () => {
        const entry = this.pending.get(id);
        if (entry) {
          socket.send(JSON.stringify({ type: 'cancel', requestId: id, agentToken: body.agentToken }));
          entry.reject(new Error('CLIENT_CANCELLED'));
        }
      };
      try {
        const result = await new Promise((resolve, reject) => {
          const timer = setTimeout(() => reject(new Error('GATEWAY_TIMEOUT_UNKNOWN_OUTCOME')), Math.min(expiry - Date.now(), 130_000));
          this.pending.set(id, { resolve, reject, timer });
          request.signal.addEventListener('abort', aborted, { once: true });
          socket.send(JSON.stringify({ type: 'invoke', id, agentToken: body.agentToken, request: body.request }));
        });
        return json(result);
      } catch (error) { return json({ error: (error as Error).message }, 504); }
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
    if (typeof message !== 'string' || message.length > 1_048_576) { socket.close(1009, 'Envelope too large'); return; }
    try {
      const body = JSON.parse(message);
      if (body.type === 'hello') {
        if (!Array.isArray(body.capabilities) || body.capabilities.length > 128) throw new Error('Invalid catalog');
        for (const cap of body.capabilities as Capability[]) {
          if (!/^[a-z][a-z0-9]*(\.[a-z][a-z0-9_]*)+$/.test(cap.descriptor.id)) throw new Error('Invalid capability');
        }
        await this.ctx.storage.put('capabilities', body.capabilities);
      } else if (body.type === 'result' || body.type === 'error') {
        const pending = this.pending.get(body.id);
        if (pending) pending.resolve(body.type === 'result' ? body.record : { error: body.code });
      }
    } catch { socket.close(1008, 'Invalid device envelope'); }
  }
  webSocketClose(socket: WebSocket, code: number, reason: string, wasClean: boolean): void {
    // An old socket closing after replacement must not reject the new socket's requests.
    if (!this.ctx.getWebSockets('device').some(s => s !== socket && s.readyState === 1)) {
      for (const p of this.pending.values()) p.reject(new Error('DEVICE_DISCONNECTED_UNKNOWN_OUTCOME'));
    }
  }
  webSocketError(socket: WebSocket): void { socket.close(1011, 'Transport failure'); }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      const url = new URL(request.url);
      if (url.pathname === '/health') return json({ service: 'SIConnect', authentication: 'private-bearer', oauth: false });
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
      if (url.pathname !== '/mcp') return json({ error: 'NOT_FOUND' }, 404);
      const principal = await authenticate(request, env);
      if (!principal) return json({ error: 'UNAUTHORIZED' }, 401);
      const token = bearer(request)!;
      const bridge = env.DEVICES.get(env.DEVICES.idFromName(principal.deviceId));
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
              body: JSON.stringify({ agentId: principal.id, agentToken: token, request: {
                requestId, traceId: requestId, capabilityId: descriptor.id, arguments: args, expiresAtMs: Date.now() + 120_000
              } })
            }));
            const record = await response.json() as Record<string, unknown>;
            return { content: [{ type: 'text', text: JSON.stringify(record) }], structuredContent: record,
              isError: !response.ok || (record.state !== 'SUCCEEDED' && record.state !== 'AWAITING_APPROVAL') };
          });
        }
        return server;
      }, { legacy: 'stateless', responseMode: 'json', maxRequestBodySize: 131072 });
      try { return await handler.fetch(request); } finally { await handler.close(); }
    } catch { return json({ error: 'INVALID_REQUEST' }, 400); }
  }
} satisfies ExportedHandler<Env>;
