// 后端接口客户端：只做「发请求 + 记原始报文」，断言一律留在用例里。
// 记录 lastHttp / lastBody 供失败原因里直接贴服务器原话，避免二次复现。
//
// v2 起支持会话 Cookie：同一个 Api 实例可以代表「匿名 / 本人 / 非本人」三种上下文。
// Cookie 由调用方显式传入（见 qa/lib/auth.mjs），客户端不自动持久化 ——
// 测试里同时存在多个身份，隐式全局 cookie 会让用例互相污染。

export const BASE = process.env.FINDIT_API_BASE ?? 'http://localhost:18080';

export class Api {
  constructor(base = BASE, { cookie = '', label = 'anon' } = {}) {
    this.base = base.replace(/\/+$/, '');
    this.cookie = cookie;
    this.label = label;
  }

  withCookie(cookie, label = this.label) {
    return new Api(this.base, { cookie, label });
  }

  async request(method, path, { body, headers } = {}) {
    const url = `${this.base}${path}`;
    const init = { method, headers: { Accept: 'application/json', ...(headers || {}) } };
    if (this.cookie) init.headers.Cookie = this.cookie;
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = typeof body === 'string' ? body : JSON.stringify(body);
    }
    const res = await fetch(url, init);
    const text = await res.text();
    let json = null;
    try {
      json = JSON.parse(text);
    } catch {
      /* 非 JSON 响应（如框架默认错误页）——保留 text 供诊断 */
    }
    const setCookies = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [];
    return {
      status: res.status,
      body: json,
      raw: text,
      url,
      method,
      headers: res.headers,
      setCookies,
    };
  }

  get(p, o) {
    return this.request('GET', p, o);
  }
  post(p, o) {
    return this.request('POST', p, o);
  }
  put(p, o) {
    return this.request('PUT', p, o);
  }
  patch(p, o) {
    return this.request('PATCH', p, o);
  }
  del(p, o) {
    return this.request('DELETE', p, o);
  }
}

/** 统一成功判据（契约 §1）：HTTP 2xx 且 code === 0 */
export function isOk(res) {
  return res.status >= 200 && res.status < 300 && res.body && res.body.code === 0;
}

/** 把「HTTP + code + message + data 摘要」压成一行，失败原因里用它。 */
export function brief(res) {
  if (!res.body) return `HTTP ${res.status}，非 JSON 响应：${String(res.raw).slice(0, 160)}`;
  const { code, message, requestId } = res.body;
  const fe = res.body?.data?.fieldErrors;
  const feTxt = Array.isArray(fe) ? ` fieldErrors=${JSON.stringify(fe)}` : '';
  return `HTTP ${res.status} / code=${code} / message=${JSON.stringify(message)}${feTxt} / requestId=${requestId}`;
}

export function expectCode(res, httpStatus, code, label) {
  if (res.status !== httpStatus || !res.body || res.body.code !== code) {
    throw new Error(`${label}：期望 HTTP ${httpStatus} + code ${code}，实际 ${brief(res)}`);
  }
}

export function fieldReasons(res) {
  const fe = res.body?.data?.fieldErrors ?? [];
  return Object.fromEntries(fe.map((f) => [f.field, f.reason]));
}

/** 从 Set-Cookie 里取 JSESSIONID=…，用于手工构造后续请求的 Cookie 头。 */
export function sessionCookieFrom(res) {
  for (const raw of res.setCookies ?? []) {
    const first = raw.split(';')[0].trim();
    if (first.startsWith('JSESSIONID=')) return first;
  }
  return null;
}
