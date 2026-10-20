import { sha256, type Env } from './auth.js';

export interface Invitation { hash: string; agentId: string; expiresAt: number; sealed: string }
export interface PhoneAuthorization { agentId: string; deviceId: string; agentToken: string }

/** The invitation code is the decryption key; the gateway stores only its hash and ciphertext. */
export async function consumeInvitation(code: string, env: Env): Promise<PhoneAuthorization> {
  const match = /^([A-Za-z0-9_-]{1,100})\.(pair_[A-Za-z0-9_-]{24})$/.exec(code);
  if (!match) throw new Error('INVALID_PAIRING');
  const bridge = env.DEVICES.get(env.DEVICES.idFromName(match[1]));
  const response = await bridge.fetch(new Request('https://internal/pairing/consume', { method: 'POST', body: JSON.stringify({ hash: await sha256(code) }) }));
  if (!response.ok) throw new Error('INVALID_PAIRING');
  const invitation = await response.json() as Invitation;
  const bytes = Uint8Array.from(atob(invitation.sealed.replaceAll('-', '+').replaceAll('_', '/')), c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey('raw', await crypto.subtle.digest('SHA-256', new TextEncoder().encode(code)), 'AES-GCM', false, ['decrypt']);
  const plaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: bytes.slice(0, 12), additionalData: new TextEncoder().encode(`${match[1]}|${invitation.agentId}|${invitation.expiresAt}`) }, key, bytes.slice(12));
  const props = JSON.parse(new TextDecoder().decode(plaintext)) as PhoneAuthorization;
  if (props.deviceId !== match[1] || props.agentId !== invitation.agentId || !/^[A-Za-z0-9_-]{20,300}$/.test(props.agentToken)) throw new Error('INVALID_PAIRING');
  return props;
}
