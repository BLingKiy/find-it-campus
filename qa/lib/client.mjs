// 后端接口客户端：只做「发请求 + 记原始报文」，断言一律留在用例里。
// 记录 lastHttp / lastBody 供失败原因里直接贴服务器原话，避免二次复现。

export const BASE = process.env.FINDIT_API_BASE ?? 'http://localhost:18080';

export class Api {
  constructor(base = BASE) {
    this.base = base.replace(/\/+$/, '');
  }

  async request(method, path, { body, headers } = {}) {
    const url = `${this.base}${path}`;
    const init = { method, headers: { Accept: 'application/json', ...(headers || {}) } };
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
    return { status: res.status, body: json, raw: text, url, method };
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
