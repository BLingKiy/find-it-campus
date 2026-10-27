package com.findit.campus.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 登录门禁的规则表（B2 验收：写接口 401、列表/详情保持匿名可读）。
 *
 * <p>为什么单独测这张表：规则决定"整站访问策略"，一条写错的 {@code startsWith} 就可能让某个
 * 写接口变成匿名可写。这类错误不会在业务测试里暴露（业务测试都带着会话），只能直接对规则做断言。
 */
@DisplayName("AuthPaths 路径规则：写默认拦、读默认放")
class AuthPathsRulesTest {

    @Test
    @DisplayName("写接口无例外一律要求登录：POST /api/records、PUT/PATCH/DELETE /api/records/{id}、POST /api/match")
    void writeEndpointsRequireLogin() {
        assertThat(requiresLogin("POST", "/api/records")).isTrue();
        assertThat(requiresLogin("PUT", "/api/records/1")).isTrue();
        assertThat(requiresLogin("PATCH", "/api/records/1/status")).isTrue();
        assertThat(requiresLogin("DELETE", "/api/records/1")).isTrue();
        assertThat(requiresLogin("POST", "/api/match")).isTrue();
        // 未来新增的写接口即使忘了登记也必须被拦（fail-closed）：这条断言就是那个"忘了"的保险
        assertThat(requiresLogin("POST", "/api/not-yet-designed")).isTrue();
    }

    @Test
    @DisplayName("匿名可写只有白名单：登录、登出、运维扫描")
    void whitelistedWritesStayPublic() {
        assertThat(requiresLogin("POST", "/api/auth/login")).isFalse();
        assertThat(requiresLogin("POST", "/api/auth/logout")).isFalse();
        assertThat(requiresLogin("POST", "/api/ops/pair-scan")).isFalse();
    }

    @Test
    @DisplayName("读接口默认匿名：列表、详情、分类、健康检查")
    void publicReadsStayAnonymous() {
        assertThat(requiresLogin("GET", "/api/records")).isFalse();
        assertThat(requiresLogin("GET", "/api/records/13")).isFalse();
        assertThat(requiresLogin("GET", "/api/categories")).isFalse();
        assertThat(requiresLogin("GET", "/api/health")).isFalse();
    }

    @Test
    @DisplayName("私有读显式登记：me / 我的启事 / 通知 / pair 联系方式")
    void privateReadsRequireLogin() {
        assertThat(requiresLogin("GET", "/api/auth/me")).isTrue();
        assertThat(requiresLogin("GET", "/api/my/records")).isTrue();
        assertThat(requiresLogin("GET", "/api/notifications")).isTrue();
        assertThat(requiresLogin("GET", "/api/pairs/7/contact")).isTrue();
    }

    @Test
    @DisplayName("前缀匹配不误伤邻居：/api/my 不吞 /api/mystery")
    void prefixDoesNotOverMatch() {
        assertThat(requiresLogin("GET", "/api/mystery")).isFalse();
        assertThat(requiresLogin("GET", "/api/mystery/records")).isFalse();
    }

    @Test
    @DisplayName("OPTIONS 预检不拦（浏览器预检不带 Cookie），非 /api 路径不参与判定")
    void preflightAndNonApiPathsAreIgnored() {
        assertThat(requiresLogin("OPTIONS", "/api/records")).isFalse();
        assertThat(requiresLogin("POST", "/index.html")).isFalse();
    }

    @Test
    @DisplayName("有 context path 时按应用内路径判定（部署在 /findit 子路径也不会绕开鉴权）")
    void contextPathIsStripped() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/findit/api/records");
        request.setContextPath("/findit");
        assertThat(AuthPaths.requiresLogin(request)).isTrue();

        MockHttpServletRequest publicRead = new MockHttpServletRequest("GET", "/findit/api/records");
        publicRead.setContextPath("/findit");
        assertThat(AuthPaths.requiresLogin(publicRead)).isFalse();
    }

    private static boolean requiresLogin(String method, String path) {
        return AuthPaths.requiresLogin(new MockHttpServletRequest(method, path));
    }
}
