package com.findit.campus.auth.web;

import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 登录门禁的<b>规则表</b>：哪些请求必须先有会话。只有数据，没有 IO —— 便于直接单测。
 *
 * <p><b>规则是"写默认拦、读默认放"</b>（fail-closed on writes）：
 * <ul>
 *   <li>非 GET/HEAD 的 {@code /api/**} 请求<b>默认要求登录</b>，例外只有一张显式白名单
 *       （登录、登出、运维扫描）。将来新增写接口忘了登记，也不会变成"匿名可写" ——
 *       匿名可写是安全问题，匿名只读只是产品问题，两种错误的代价不对称。</li>
 *   <li>GET 默认匿名可读（契约 §3：列表、详情、分类保持匿名），只有列在
 *       {@link #LOGIN_REQUIRED_READS} 里的读接口才要求登录（me / 我的启事 / 通知 / pair 联系方式）。
 *       新增"私有读"接口必须显式登记 —— 漏了的后果是"多返回了公开数据"，评审时能看出来。</li>
 * </ul>
 *
 * <p>为什么规则独立于"在哪里执行"：门禁由 {@link AuthGateFilter} 在<b>路由之前</b>执行，
 * 因此一个"需要登录但还没实现"的路径（如 C1 的 {@code /api/my/records}）匿名访问也是 401 而不是 404 ——
 * 未登录者连"这个路由存不存在"都不该知道。规则表与执行点分开，换成拦截器/AOP 时规则不用重写。
 */
public final class AuthPaths {

    /** 需要登录的读接口：精确匹配或前缀匹配（见 {@link #matches}）。 */
    static final List<String> LOGIN_REQUIRED_READS = List.of(
            "/api/auth/me",
            "/api/my",
            "/api/notifications",
            "/api/pairs");

    /**
     * 匿名可写的例外路径（精确匹配）。
     *
     * <p>{@code /api/auth/logout} 必须在里面：契约 §4.10 要求未登录调用也 200（幂等）。
     * {@code /api/ops/pair-scan} 也在里面：它是运维接口，用 {@code X-Ops-Token} 认证，不是用户会话；
     * 放进白名单是为了让它<b>不</b>被会话门禁改写行为（D1 落地时按 token 判 401/404）。
     */
    static final Set<String> PUBLIC_WRITE_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/logout",
            "/api/ops/pair-scan",
            "/api/ops/reviews");

    private static final String API_PREFIX = "/api/";

    private AuthPaths() {
    }

    /**
     * 判定单个请求是否需要登录。
     *
     * <p>抽成静态方法是为了让"规则表"能直接单测（不需要起 Spring 上下文、不需要 mock 会话）。
     */
    public static boolean requiresLogin(HttpServletRequest request) {
        String method = request.getMethod();
        if (method == null) {
            return false;
        }
        // 预检请求不带 Cookie，拦它等于让浏览器直接 CORS 失败
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return false;
        }
        String path = pathOf(request);
        if (!path.startsWith(API_PREFIX)) {
            return false;
        }
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            // 运维入口按前缀放行（/api/ops/reviews/{id}/approve），认证仍由 X-Ops-Token 完成；
            // 这里只让"会话门禁"不介入，不是让它匿名可写。
            return PUBLIC_WRITE_PATHS.stream().noneMatch(prefix -> matches(path, prefix));
        }
        return LOGIN_REQUIRED_READS.stream().anyMatch(prefix -> matches(path, prefix));
    }

    /** 去掉 context path，得到以 {@code /} 开头的应用内路径。 */
    private static String pathOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (path == null) {
            return "";
        }
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    /**
     * 前缀匹配：登记为 {@code /api/my} 时，{@code /api/my} 与 {@code /api/my/records} 都命中，
     * 但 {@code /api/mystery} 不命中（避免"前缀写短了就误伤隔壁接口"）。
     */
    private static boolean matches(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
