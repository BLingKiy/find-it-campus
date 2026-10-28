package com.findit.campus.auth.session;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

import com.findit.campus.auth.domain.SessionUser;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * 会话读写的<b>唯一</b>入口：session 属性名、登录时换 session id、登出时清 Cookie，三件事只在这里做。
 *
 * <p>为什么集中：如果每个 controller / 拦截器各自 {@code session.getAttribute("user")}，
 * 属性名打错一次就是"永远未登录"，而这种 bug 在编译期完全看不出来。集中后属性名只有一处，
 * 取不到用户时的出口（401 + 10006）也只有一处。
 *
 * <p>为什么用服务端 {@code HttpSession} 而不是 JWT（V2-ADR-002）：登出/失效要立即生效，
 * 内存会话天然可吊销；代价是单实例、重启即失效（已写进部署说明，不在客户端补偿）。
 */
public final class SessionAuth {

    /**
     * session 属性名：带包名前缀，避免与容器或其他库的同名属性撞车
     * （例如某些框架会往 session 里放 {@code "user"}）。
     */
    public static final String SESSION_ATTRIBUTE = "com.findit.campus.auth.SESSION_USER";

    /** 容器默认的会话 Cookie 名（Tomcat / 单实例部署口径，见 docs/design/v2-auth-session.md）。 */
    public static final String SESSION_COOKIE = "JSESSIONID";

    /**
     * 清 Cookie 时用的 SameSite：与 {@code server.servlet.session.cookie.same-site=lax} 保持一致。
     *
     * <p>删 Cookie 的匹配只看 name + domain + path，不看 SameSite/Secure，所以这里写成固定值不影响
     * "能不能删掉"；保持一致是为了让响应头在抓包/证据里不出现两套口径。
     */
    private static final String SAME_SITE = "Lax";

    private SessionAuth() {
    }

    /**
     * 当前会话用户；没有会话、会话里没这个属性、属性类型不对，一律返回 {@code null}
     * （类型不对当未登录处理：宁可在登录页重新登录，也不要把一个来路不明的对象当成身份）。
     */
    public static SessionUser currentUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(SESSION_ATTRIBUTE);
        return value instanceof SessionUser user ? user : null;
    }

    /** 取当前用户，未登录抛 401 + 10006（"请先登录"）。 */
    public static SessionUser requireUser(HttpServletRequest request) {
        SessionUser user = currentUser(request);
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    /**
     * 登录成功：建会话 → <b>换 session id</b> → 写身份。
     *
     * <p>{@code changeSessionId()} 是会话固定攻击（session fixation）的标准防护：攻击者先把自己的
     * JSESSIONID 塞给受害者，受害者登录后若沿用同一个 id，攻击者手里的 id 就成了已登录会话。
     * 先 {@code getSession(true)} 再换 id 是 Servlet 规范要求的顺序（没有会话时换 id 会抛
     * {@code IllegalStateException}）。
     */
    public static void signIn(HttpServletRequest request, SessionUser user) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(SESSION_ATTRIBUTE, user);
    }

    /**
     * 登出：会话存在就让它失效；已经失效/从未建立都当成功（幂等）。
     *
     * <p>为什么吃 {@code IllegalStateException}：并发两次登出、或会话刚超时，容器会抛这个异常；
     * 契约 §4.10 要求"未登录调用也 200、重复调用 200"，把它当失败回 500 是错的。
     */
    public static void signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        try {
            session.invalidate();
        } catch (IllegalStateException alreadyInvalidated) {
            // 并发登出 / 已超时：目标状态已经达成，不需要再做什么
        }
    }

    /**
     * 让浏览器丢掉会话 Cookie 的响应头（{@code Max-Age=0}）。
     *
     * <p>为什么 {@code invalidate()} 之外还要显式删 Cookie：容器失效会话时不会主动下发删除指令，
     * 浏览器会继续带着那个已经无意义的 {@code JSESSIONID}，直到自然过期 —— 现象是"退出后
     * network 面板里还挂着一个过期 id"，容易被误读成"退出没生效"。属性与登录时保持一致
     * （{@code Path=/}、{@code HttpOnly}、{@code SameSite=Lax}），只把 Max-Age 置 0。
     */
    public static ResponseCookie clearedSessionCookie() {
        return ResponseCookie.from(SESSION_COOKIE, "")
                .path("/")
                .httpOnly(true)
                .sameSite(SAME_SITE)
                .maxAge(Duration.ZERO)
                .build();
    }
}
