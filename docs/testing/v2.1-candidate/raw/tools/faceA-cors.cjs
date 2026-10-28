// Face A: deployment CORS checks through nginx :8088.
const fs = require('node:fs');

const BASE = (process.env.M6_BASE || 'http://127.0.0.1:8088').replace(/\/+$/, '');
const OUT = process.env.M6_OUT || '.';

function cookieOfFromSetCookie(setCookies) {
  const m = (setCookies || []).join(';').match(/JSESSIONID=[^;,\s]+/);
  if (!m) throw new Error('login response has no JSESSIONID');
  return m[0];
}

async function call(method, p, { body, cookie, origin, extraHeaders } = {}) {
  const headers = { Accept: 'application/json', ...(extraHeaders || {}) };
  if (cookie) headers.Cookie = cookie;
  if (origin) headers.Origin = origin;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(`${BASE}${p}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  return {
    method,
    path: p,
    requestOrigin: origin ?? null,
    status: res.status,
    acao: res.headers.get('access-control-allow-origin'),
    acac: res.headers.get('access-control-allow-credentials'),
    acam: res.headers.get('access-control-allow-methods'),
    vary: res.headers.get('vary'),
    body: text.slice(0, 600),
    setCookie: res.headers.getSetCookie ? res.headers.getSetCookie() : [res.headers.get('set-cookie') || ''].filter(Boolean),
  };
}

const recordBody = (marker) => ({
  type: 'LOST',
  title: `M6 CORS ${marker} 黑色钱包`,
  description: `M6 CORS ${marker} 在图书馆三楼丢失黑色皮质钱包，内有工行卡`,
  location: '图书馆三楼',
  occurredAt: '2026-10-25 15:00:00',
  contact: '13800000002',
  publisherName: '演示乙',
});

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const login = await call('POST', '/api/auth/login', { body: { studentNo: 'demo01', password: 'Demo@123456' } });
  const cookie = cookieOfFromSetCookie(login.setCookie);
  const results = { base: BASE, login: { status: login.status, body: login.body }, checks: {} };

  // 1) same-origin (deployment source) -> must succeed and echo the origin
  const same = await call('POST', '/api/records', { cookie, origin: 'http://127.0.0.1:8088', body: recordBody('SAME') });
  results.checks.sameOrigin = same;

  // 2) no Origin -> must succeed, no CORS headers required
  const none = await call('POST', '/api/records', { cookie, body: recordBody('NONE') });
  results.checks.noOrigin = none;

  // 3) a genuine cross-origin but whitelisted loopback origin -> must echo ACAO + credentials
  const cross = await call('POST', '/api/records', { cookie, origin: 'http://localhost:5173', body: recordBody('CROSS') });
  results.checks.crossOriginLoopback = cross;

  // 4) external Origin -> must be 403 and must not echo the origin
  const evil = await call('POST', '/api/records', { cookie, origin: 'http://evil.example', body: recordBody('EVIL') });
  results.checks.externalOrigin = evil;

  // 5) preflight from the deployment origin
  const preflight = await call('OPTIONS', '/api/records', {
    origin: 'http://127.0.0.1:8088',
    extraHeaders: {
      'Access-Control-Request-Method': 'POST',
      'Access-Control-Request-Headers': 'content-type',
    },
  });
  results.checks.preflight = preflight;
  const preflightCross = await call('OPTIONS', '/api/records', {
    origin: 'http://localhost:5173',
    extraHeaders: {
      'Access-Control-Request-Method': 'POST',
      'Access-Control-Request-Headers': 'content-type',
    },
  });
  results.checks.preflightCrossOrigin = preflightCross;

  results.verdict = {
    // The deployment origin is same-origin to nginx (Host is forwarded as $http_host),
    // so Spring correctly treats it as a non-CORS request: 200 and no ACAO needed.
    sameOriginOk: same.status === 200,
    sameOriginNot403: same.status !== 403,
    sameOriginNoAcaoBecauseSameOrigin: !same.acao,
    noOriginOk: none.status === 200,
    crossOriginLoopbackOk: cross.status === 200,
    crossOriginAcao: cross.acao === 'http://localhost:5173',
    crossOriginCredentials: cross.acac === 'true',
    externalForbidden: evil.status === 403,
    externalNoAcao: !evil.acao,
    preflightSameOriginOk: (preflight.status === 200 || preflight.status === 204),
    preflightCrossOk: (preflightCross.status === 200 || preflightCross.status === 204)
      && preflightCross.acao === 'http://localhost:5173'
      && preflightCross.acac === 'true'
      && (preflightCross.acam || '').includes('POST'),
  };
  results.passed = Object.values(results.verdict).every(Boolean);
  fs.writeFileSync(`${OUT}/faceA-cors.json`, JSON.stringify(results, null, 2), 'utf8');
  console.log(JSON.stringify(results, null, 2));
  if (!results.passed) process.exitCode = 1;
})().catch((e) => {
  console.error('CORS_FAIL', e.message);
  process.exit(1);
});
