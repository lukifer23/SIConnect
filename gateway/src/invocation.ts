import { sha256 } from './auth.js';
import type { PhoneAuthorization } from './pairing.js';

export const OPERATION_KEY = '_siconnectOperationKey';

/** Optional client retry identity is transport metadata, never a capability argument. */
export function invocationSchema(input: Record<string, unknown>): Record<string, unknown> {
  const properties = (input.properties ?? {}) as Record<string, unknown>;
  if (Object.hasOwn(properties, OPERATION_KEY)) throw new Error('Reserved operation key collides with a capability argument');
  return { ...input, properties: { ...properties, [OPERATION_KEY]: {
    type: 'string', minLength: 1, maxLength: 100,
    description: 'Optional stable retry key for this action. Reuse with identical arguments to retrieve the original request, including after a lost response. Use a new key for a deliberately new action. Never changes or extends the original device deadline.'
  } } };
}

export async function invocationEnvelope(authorization: PhoneAuthorization, capabilityId: string, rawArgs: unknown) {
  if (!rawArgs || typeof rawArgs !== 'object' || Array.isArray(rawArgs)) throw new Error('Capability arguments must be an object');
  const args = rawArgs as Record<string, unknown>;
  const { [OPERATION_KEY]: key, ...arguments_ } = args;
  if (key !== undefined && (typeof key !== 'string' || key.length < 1 || key.length > 100)) throw new Error('Invalid operation key');
  const hash = key === undefined ? undefined : await sha256(JSON.stringify([authorization.deviceId, authorization.agentId, key]));
  const requestId = hash === undefined ? crypto.randomUUID() : `mcp:${hash}`;
  const transportExpiresAtMs = Date.now() + 15 * 60_000;
  return {
    agentId: authorization.agentId, agentToken: authorization.agentToken, transportExpiresAtMs,
    request: {
      requestId, traceId: requestId, capabilityId, arguments: arguments_,
      // A keyed retry always omits explicit execution expiry. The phone freezes its normal
      // default at first admission; transport waiting has a separate per-attempt deadline.
      ...(hash === undefined ? { expiresAtMs: transportExpiresAtMs } : { idempotencyKey: `mcp:${hash}` })
    }
  };
}
