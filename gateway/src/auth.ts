export interface Principal {
  id: string;
  deviceId: string;
  tokenSha256: string;
  expiresAt: number;
  revoked?: boolean;
}
export interface Env {
  DEVICES: DurableObjectNamespace;
  OAUTH_KV: KVNamespace;
  PUBLIC_ORIGIN?: string;
  AGENTS_JSON: string;
  DEVICE_ENROLL_TOKEN: string;
}
export async function sha256(value: string): Promise<string> {
  const bytes = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)));
  return [...bytes].map(b => b.toString(16).padStart(2, '0')).join('');
}
export function constantEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let difference = 0;
  for (let i = 0; i < a.length; i++) difference |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return difference === 0;
}
export function bearer(request: Request): string | null {
  const match = /^Bearer ([A-Za-z0-9_-]{20,300})$/.exec(request.headers.get('Authorization') ?? '');
  return match?.[1] ?? null;
}
export async function authenticate(request: Request, env: Env): Promise<Principal | null> {
  const token = bearer(request);
  if (!token) return null;
  const hash = await sha256(token);
  const principals: Principal[] = JSON.parse(env.AGENTS_JSON || '[]');
  return principals.find(p => constantEqual(p.tokenSha256, hash) && !p.revoked && p.expiresAt > Date.now() && /^[a-zA-Z0-9_-]{1,100}$/.test(p.deviceId)) ?? null;
}
