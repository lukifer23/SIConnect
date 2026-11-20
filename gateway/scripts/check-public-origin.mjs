import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

export async function checkPublicOrigin(address) {
  const url = new URL(address);
  assert.ok(url.protocol === 'https:' || (url.protocol === 'http:' && url.hostname === '127.0.0.1'),
    'Use the published HTTPS origin or a loopback test Worker');
  assert.ok(!url.username && !url.password && !url.search && !url.hash && url.pathname === '/',
    'Supply the origin without credentials, query parameters or a path');
  const origin = url.origin;
  const metadataPath = '/.well-known/oauth-protected-resource/mcp';
  async function get(path, status) {
    const response = await fetch(origin + path, { redirect: 'manual', signal: AbortSignal.timeout(10000) });
    assert.equal(response.status, status, path + ' returned HTTP ' + response.status);
    return response;
  }
  const [health, protectedResource, discovery, challenge] = await Promise.all([
    get('/health', 200).then(r => r.json()),
    get(metadataPath, 200).then(r => r.json()),
    get('/.well-known/oauth-authorization-server', 200).then(r => r.json()),
    get('/mcp', 401),
  ]);
  assert.equal(health.service, 'SIConnect');
  assert.equal(health.oauth, true);
  assert.ok(challenge.headers.get('www-authenticate')?.includes('resource_metadata="' + origin + metadataPath + '"'),
    'MCP challenge must advertise the public resource metadata URL');
  assert.equal(protectedResource.resource, origin + '/mcp');
  assert.deepEqual(protectedResource.authorization_servers, [origin]);
  assert.equal(discovery.issuer, origin);
  assert.equal(discovery.authorization_endpoint, origin + '/authorize');
  assert.equal(discovery.token_endpoint, origin + '/token');
  assert.equal(discovery.registration_endpoint, origin + '/oauth/register');
  assert.ok(discovery.protected_resources?.includes(origin + '/mcp'));
  return origin;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const origin = await checkPublicOrigin(process.argv[2]);
    console.log('SIConnect health, MCP challenge and OAuth discovery agree on ' + origin);
  } catch (error) {
    console.error('SIConnect public origin check failed. ' + error.message);
    process.exitCode = 1;
  }
}
