// 登录夹具：匿名 / 本人（demo01）/ 非本人（demo02、demo03）四套上下文。
//
// 设计取舍：每个身份一个独立 Api 实例，Cookie 显式挂在实例上。
// 不用全局 cookie jar —— 同一条用例里经常要「本人写完立刻用非本人读」，
// 隐式共享会让 401/403/200 三态混在一起，失败时也看不出是谁的会话。

import { Api, sessionCookieFrom } from './client.mjs';

export const PASSWORD = 'Demo@123456';
export const ACCOUNTS = {
  demo01: { id: 1, displayName: '演示甲' },
  demo02: { id: 2, displayName: '演示乙' },
  demo03: { id: 3, displayName: '演示丙' },
};

/** 登录并返回带 Cookie 的 Api；登录失败直接抛错（前置没满足时不要继续跑后面的用例）。 */
export async function login(base, studentNo, password = PASSWORD) {
  const probe = new Api(base);
  const res = await probe.post('/api/auth/login', { body: { studentNo, password } });
  if (res.status !== 200 || res.body?.code !== 0) {
    throw new Error(`登录夹具失败 ${studentNo}：HTTP ${res.status} / code=${res.body?.code} / ${res.body?.message}`);
  }
  const cookie = sessionCookieFrom(res);
  if (!cookie) throw new Error(`登录夹具失败 ${studentNo}：响应没有 JSESSIONID Set-Cookie`);
  return new Api(base, { cookie, label: studentNo });
}

/** 一次建立四套上下文；任何一套登录失败都视为环境不可用。 */
export async function contexts(base = undefined) {
  const anon = new Api(base);
  const demo01 = await login(base, 'demo01');
  const demo02 = await login(base, 'demo02');
  const demo03 = await login(base, 'demo03');
  return { anon, demo01, demo02, demo03 };
}
