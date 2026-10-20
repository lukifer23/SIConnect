import OAuthProvider, { AuthorizationError, CimdFetchError, type OAuthHelpers } from '@cloudflare/workers-oauth-provider';
import type { Env } from './auth.js';
import { consumeInvitation, type PhoneAuthorization } from './pairing.js';
import { serveMcp } from './index.js';

const escape = (value: string) => value.replace(/[&<>"']/g, c => `&#${c.charCodeAt(0)};`);
const headers = { 'Cache-Control': 'no-store', 'Content-Type': 'text/plain; charset=utf-8', 'Referrer-Policy': 'no-referrer' };
type AuthorizedContext = ExecutionContext & { props: PhoneAuthorization; auth: { scope: string[] } };
type OAuthEnv = Env & { OAUTH_PROVIDER: OAuthHelpers };

export async function oauthFetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const origin = env.PUBLIC_ORIGIN ?? new URL(request.url).origin;
  const provider = new OAuthProvider<OAuthEnv>({
    apiRoute: '/mcp', authorizeEndpoint: '/authorize', tokenEndpoint: '/token', clientRegistrationEndpoint: '/oauth/register',
    scopesSupported: ['phone:control', 'offline_access'], requiredScopes: ['phone:control'],
    resourceMetadata: { resource: origin + '/mcp', resource_name: 'SIConnect phone' },
    accessTokenTTL: 3600, refreshTokenTTL: 30 * 86400, clientIdMetadataDocumentEnabled: true,
    apiHandler: {
      async fetch(req, bindings, execution) {
        const auth = execution as AuthorizedContext;
        if (!auth.auth.scope.includes('phone:control')) return new Response('Insufficient scope', { status: 403, headers });
        if (!auth.props?.agentId || !auth.props.deviceId || !auth.props.agentToken) return new Response('Invalid grant', { status: 401, headers });
        return serveMcp(req, bindings, auth.props);
      }
    },
    defaultHandler: {
      async fetch(req, bindings) {
        const path = new URL(req.url).pathname;
        if (path !== '/authorize') return new Response('Not found', { status: 404, headers });
        const oauth = bindings.OAUTH_PROVIDER;
        try {
          if (req.method === 'GET') {
            const authorization = await oauth.parseAuthRequest(req);
            const details = await oauth.describeConsent(authorization);
            const consent = await oauth.beginConsent(authorization);
            consent.headers.set('Content-Type', 'text/html; charset=utf-8');
            consent.headers.set('Cache-Control', 'no-store');
            consent.headers.set('Referrer-Policy', 'no-referrer');
            const publisher = details.clientDomain ? `Published by <strong>${escape(details.clientDomain)}</strong>.` : 'The app name is self-registered and unverified.';
            return new Response(`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Connect SIConnect</title>
<style>body{color:#edf9f6;background:#0d1218;font:17px system-ui;max-width:480px;margin:8vh auto;padding:24px}input,button{font:inherit;border-radius:12px;padding:14px;box-sizing:border-box}input{width:100%;background:#202d39;color:white;border:1px solid #6a807a}button{margin:14px 8px 0 0;background:#8adcc3;border:0}small{color:#bccdc8}label{display:block;margin:20px 0 8px}</style>
<h1>Connect your phone</h1><h2>${escape(details.clientName)}</h2><p>${publisher} Access returns to <strong>${escape(details.redirectHost)}</strong>.</p>
${details.redirectIsLoopback ? '<p>This connects an app on your computer. Continue only if you started this connection.</p>' : ''}
<p>In SIConnect on your phone, tap <strong>Connection details → Pair another bot</strong>. Enter its temporary pairing code below.</p>
<p>This grants phone control through your phone’s current permissions and approvals. Revoke this bot in SIConnect at any time.</p>
<form method="post" action="/authorize"><input type="hidden" name="handle" value="${escape(consent.handle)}"><label for="code">Phone pairing code</label><input id="code" name="code" type="password" autocomplete="off" required maxlength="160"><p><small>Requested scopes: ${details.scope.map(escape).join(', ')}</small></p><button name="decision" value="approve">Connect</button><button name="decision" value="deny" formnovalidate>Cancel</button></form></html>`, { headers: consent.headers });
          }
          if (req.method !== 'POST') return new Response('Method not allowed', { status: 405, headers });
          if (Number(req.headers.get('Content-Length') ?? '0') > 8192) return new Response('Form too large', { status: 413, headers });
          const text = await boundedText(req, 8192);
          if (text.length > 8192) return new Response('Form too large', { status: 413, headers });
          const form = new URLSearchParams(text);
          const handle = form.get('handle') ?? '';
          if (form.get('decision') !== 'approve') {
            const denied = await oauth.denyConsent(req, handle);
            return new Response(null, { status: 302, headers: denied.headers });
          }
          // Browser-bound one-use consent is verified before consuming phone authority.
          const approved = await oauth.approveConsent(req, handle);
          let props: PhoneAuthorization;
          try { props = await consumeInvitation((form.get('code') ?? '').trim(), bindings); }
          catch { return new Response('Pairing code expired, was already used, or is incorrect. Start the connection again and generate a fresh code on your phone.', { status: 401, headers }); }
          if (!approved.request.scope.includes('phone:control')) return new Response('Phone control scope required', { status: 400, headers });
          const { redirectTo } = await oauth.completeAuthorization({ request: approved.request, userId: props.agentId, metadata: { deviceId: props.deviceId }, scope: approved.request.scope, props });
          approved.headers.set('Location', redirectTo);
          return new Response(null, { status: 302, headers: approved.headers });
        } catch (error) {
          if (error instanceof AuthorizationError && error.redirectTo) return Response.redirect(error.redirectTo, 302);
          if (error instanceof AuthorizationError || error instanceof CimdFetchError) return new Response(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Reconnect SIConnect</title><style>body{font:18px system-ui;background:#0d1218;color:#edf9f6;max-width:480px;margin:10vh auto;padding:24px}</style><h1>Start the connection again</h1><p>${error instanceof AuthorizationError ? escape(error.description) : 'Client identity could not be verified'}</p><p>Return to SIConnect in your bot and choose <strong>Continue connecting</strong>. In the phone app, create a fresh pairing code if its displayed expiration has passed.</p><p>Your existing phone permissions and other connected bots are unchanged.</p>`, { status: 400, headers: { ...headers, 'Content-Type': 'text/html; charset=utf-8', 'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'" } });
          throw error;
        }
      }
    }
  });
  return provider.fetch(request, env as OAuthEnv, ctx);
}

async function boundedText(request: Request, limit: number): Promise<string> {
  const reader = request.body?.getReader();
  if (!reader) return '';
  const decoder = new TextDecoder(); let length = 0, text = '';
  try {
    while (true) {
      const chunk = await reader.read(); if (chunk.done) return text + decoder.decode();
      length += chunk.value.byteLength;
      if (length > limit) { await reader.cancel(); throw new AuthorizationError('invalid_request', { description: 'Form too large' }); }
      text += decoder.decode(chunk.value, { stream: true });
    }
  } finally { reader.releaseLock(); }
}
