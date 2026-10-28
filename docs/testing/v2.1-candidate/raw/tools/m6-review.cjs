// Publish review-gate cases and check their public visibility.
// Usage:
//   node m6-review.cjs publish <cases.json> <ids.json>
//   node m6-review.cjs check   <ids.json>
const fs = require('node:fs');

const BASE = (process.env.M6_BASE || 'http://127.0.0.1:8088').replace(/\/+$/, '');
const PASSWORD = 'Demo@123456';

function setCookies(res) {
  return res.headers.getSetCookie ? res.headers.getSetCookie() : [res.headers.get('set-cookie') || ''].filter(Boolean);
}
function cookieOf(cookies) {
  const m = (cookies || []).join(';').match(/JSESSIONID=[^;,\s]+/);
  if (!m) throw new Error('no JSESSIONID in login response');
  return m[0];
}

async function request(method, p, { body, cookie } = {}) {
  const headers = { Accept: 'application/json' };
  if (cookie) headers.Cookie = cookie;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(`${BASE}${p}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch {}
  return { status: res.status, code: json?.code ?? null, json, raw: text, setCookie: setCookies(res) };
}

async function login(studentNo) {
  const r = await request('POST', '/api/auth/login', { body: { studentNo, password: PASSWORD } });
  if (r.status !== 200 || r.code !== 0) throw new Error(`login ${studentNo}: HTTP ${r.status} ${r.raw.slice(0, 200)}`);
  return cookieOf(r.setCookie);
}

(async () => {
  const mode = process.argv[2];
  if (mode === 'publish') {
    const parsed = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
    const cases = Array.isArray(parsed) ? parsed : [parsed];
    const cookie = await login('demo01');
    const out = [];
    for (const c of cases) {
      const r = await request('POST', '/api/records', { cookie, body: c.payload });
      out.push({
        name: c.name,
        http: r.status,
        code: r.code,
        id: r.json?.data?.id ?? null,
        reviewState: r.json?.data?.reviewState ?? null,
        reviewManualRequired: r.json?.data?.reviewManualRequired ?? null,
        title: r.json?.data?.title ?? null,
        body: r.raw.slice(0, 400),
      });
    }
    fs.writeFileSync(process.argv[4], JSON.stringify(out, null, 2), 'utf8');
    console.log(JSON.stringify(out, null, 2));
  } else if (mode === 'check') {
    const parsedIds = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
    const ids = Array.isArray(parsedIds) ? parsedIds : [parsedIds];
    const cookie = await login('demo02');
    const idsOnPage = new Set();
    let total = null;
    for (let page = 1; page <= 10; page++) {
      const list = await request('GET', `/api/records?pageSize=50&page=${page}`);
      if (list.status !== 200) throw new Error(`public list page ${page}: HTTP ${list.status} ${list.raw.slice(0, 200)}`);
      const pageData = list.json?.data ?? {};
      if (total === null) total = pageData.total ?? null;
      for (const r of pageData.list ?? []) idsOnPage.add(r.id);
      if (!pageData.hasNext) break;
    }
    const out = [];
    for (const c of ids) {
      const d = await request('GET', `/api/records/${c.id}`, { cookie });
      out.push({
        name: c.name,
        id: c.id,
        publishReviewState: c.reviewState,
        inPublicList: idsOnPage.has(c.id),
        publicTotal: total,
        otherDetailHttp: d.status,
        otherDetailCode: d.code,
        otherDetailReviewState: d.json?.data?.reviewState ?? null,
      });
    }
    console.log(JSON.stringify(out, null, 2));
  } else {
    throw new Error('mode must be publish|check');
  }
})().catch((e) => {
  console.error('REVIEW_FAIL', e.message);
  process.exit(1);
});
