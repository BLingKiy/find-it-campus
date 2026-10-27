package com.findit.campus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.findit.campus.auth.domain.SessionUser;
import com.findit.campus.auth.domain.User;
import com.findit.campus.auth.mapper.UserMapper;
import com.findit.campus.auth.session.SessionAuth;
import com.findit.campus.auth.web.dto.LoginRequest;
import com.findit.campus.auth.web.dto.UserResponse;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;

/**
 * 登录的规则层（契约 §4.9）：口令校验、统一失败文案、会话建立、失败计数。
 *
 * <p>用真 {@link BCryptPasswordEncoder}（不是 mock）：这里要验的正是"bcrypt 能不能校验 seed 那种
 * {@code $2a$10$} 哈希"与"cost 是否落在契约区间"，mock 掉编码器等于把被测对象换成假货。
 *
 * <p>不连数据库：{@link UserMapper} 用 mock，SQL 的正确性由 {@code UserMapper.xml} 与真机证据保证。
 */
@DisplayName("AuthService 登录规则")
class AuthServiceTest {

    private static final String STUDENT_NO = "demo01";
    private static final String PASSWORD = "Demo@123456";
    private static final String CLIENT_IP = "127.0.0.1";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(10);

    private UserMapper userMapper;
    private LoginAttemptTracker loginAttempts;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        loginAttempts = new LoginAttemptTracker(Clock.systemUTC());
        authService = new AuthService(userMapper, encoder, loginAttempts);
    }

    @Test
    @DisplayName("正确口令 → 返回 {id,studentNo,displayName}，会话里写入本人身份")
    void correctPasswordSignsIn() {
        when(userMapper.selectByStudentNo(STUDENT_NO)).thenReturn(activeUser(encoder.encode(PASSWORD)));
        MockHttpServletRequest request = loginRequest();

        UserResponse response = authService.login(loginRequestBody(PASSWORD), request);

        assertThat(response.getStudentNo()).isEqualTo(STUDENT_NO);
        assertThat(response.getDisplayName()).isEqualTo("演示甲");
        SessionUser sessionUser = SessionAuth.currentUser(request);
        assertThat(sessionUser).isNotNull();
        assertThat(sessionUser.id()).isEqualTo(1L);
    }

    @Test
    @DisplayName("会话固定防护：登录前已有会话，登录后 session id 必须变化")
    void loginRotatesSessionId() {
        when(userMapper.selectByStudentNo(STUDENT_NO)).thenReturn(activeUser(encoder.encode(PASSWORD)));
        MockHttpServletRequest request = loginRequest();
        request.getSession(true);
        String before = request.getSession(false).getId();

        authService.login(loginRequestBody(PASSWORD), request);

        assertThat(request.getSession(false).getId()).isNotEqualTo(before);
    }

    @Test
    @DisplayName("口令错 → 401/10006 + 学号或密码不正确，且不建立会话")
    void wrongPasswordIsUniformFailure() {
        when(userMapper.selectByStudentNo(STUDENT_NO)).thenReturn(activeUser(encoder.encode(PASSWORD)));
        MockHttpServletRequest request = loginRequest();

        assertUniformFailure(() -> authService.login(loginRequestBody("wrong-password"), request));
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    @DisplayName("学号不存在 → 与口令错完全相同的 401/10006 + 同一文案（防账号枚举）")
    void unknownStudentIsUniformFailure() {
        when(userMapper.selectByStudentNo(STUDENT_NO)).thenReturn(null);

        assertUniformFailure(() -> authService.login(loginRequestBody(PASSWORD), loginRequest()));
    }

    @Test
    @DisplayName("账号停用 → 同一 401/10006 + 同一文案（不暴露\"存在但停用\"）")
    void inactiveUserIsUniformFailure() {
        User user = activeUser(encoder.encode(PASSWORD));
        user.setActive(false);
        when(userMapper.selectByStudentNo(STUDENT_NO)).thenReturn(user);

        assertUniformFailure(() -> authService.login(loginRequestBody(PASSWORD), loginRequest()));
    }

    @Test
    @DisplayName("连续 5 次错误口令 → 每次都是同一 401/10006 + 同一文案，且口令哈希一次都没被写")
    void fiveFailuresStayUniformAndNeverTouchPasswordHash() {
        String hash = encoder.encode(PASSWORD);
        when(userMapper.selectByStudentNo(STUDENT_NO)).thenReturn(activeUser(hash));

        for (int attempt = 1; attempt <= 5; attempt++) {
            MockHttpServletRequest request = loginRequest();
            assertUniformFailure(() -> authService.login(loginRequestBody("wrong-password"), request));
        }

        assertThat(loginAttempts.failureCount(STUDENT_NO, CLIENT_IP)).isEqualTo(5);
        // 只读：查询 5 次，没有任何写操作（UserMapper 本身也只有 SELECT，这里把"没有写"钉在测试里）
        verify(userMapper, times(5)).selectByStudentNo(STUDENT_NO);
        verifyNoMoreInteractions(userMapper);
    }

    @Test
    @DisplayName("库里存的不是 bcrypt（历史明文/坏行）→ 仍按凭证错返回 401，不是 500")
    void malformedStoredHashIsTreatedAsBadCredentials() {
        when(userMapper.selectByStudentNo(STUDENT_NO)).thenReturn(activeUser("plain-text-password"));

        assertUniformFailure(() -> authService.login(loginRequestBody(PASSWORD), loginRequest()));
    }

    @Test
    @DisplayName("登录成功会清掉失败计数：窗口从下一次失败重新起算")
    void successClearsFailureCounter() {
        when(userMapper.selectByStudentNo(STUDENT_NO)).thenReturn(activeUser(encoder.encode(PASSWORD)));
        assertUniformFailure(() -> authService.login(loginRequestBody("wrong"), loginRequest()));
        assertThat(loginAttempts.failureCount(STUDENT_NO, CLIENT_IP)).isEqualTo(1);

        authService.login(loginRequestBody(PASSWORD), loginRequest());

        assertThat(loginAttempts.failureCount(STUDENT_NO, CLIENT_IP)).isZero();
    }

    // ------------------------------------------------------------------

    private static void assertUniformFailure(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BizException.class)
                .satisfies(thrown -> {
                    BizException biz = (BizException) thrown;
                    assertThat(biz.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                    assertThat(biz.errorCode().httpStatus().value()).isEqualTo(401);
                    assertThat(biz.publicMessage()).isEqualTo(AuthService.BAD_CREDENTIALS_MESSAGE);
                });
    }

    private static LoginRequest loginRequestBody(String password) {
        LoginRequest request = new LoginRequest();
        request.setStudentNo(STUDENT_NO);
        request.setPassword(password);
        return request;
    }

    private static MockHttpServletRequest loginRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(CLIENT_IP);
        return request;
    }

    private static User activeUser(String passwordHash) {
        User user = new User();
        user.setId(1L);
        user.setStudentNo(STUDENT_NO);
        user.setPasswordHash(passwordHash);
        user.setDisplayName("演示甲");
        user.setActive(true);
        return user;
    }
}
