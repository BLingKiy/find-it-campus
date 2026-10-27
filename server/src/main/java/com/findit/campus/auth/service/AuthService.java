package com.findit.campus.auth.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.findit.campus.auth.domain.SessionUser;
import com.findit.campus.auth.domain.User;
import com.findit.campus.auth.mapper.UserMapper;
import com.findit.campus.auth.session.SessionAuth;
import com.findit.campus.auth.web.dto.LoginRequest;
import com.findit.campus.auth.web.dto.UserResponse;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * 登录 / 登出 / 当前用户（契约 §4.9–§4.11）。
 *
 * <p>三条硬口径：
 * <ol>
 *   <li><b>失败不区分原因</b>：学号不存在、口令错、账号停用，对外都是 401 + 10006 +
 *       "学号或密码不正确"。任何一次"这个学号不存在"的差异化文案都等于把用户名单交给撞库者。</li>
 *   <li><b>失败不改变数据</b>：本类对 {@code t_user} 只读，失败路径连一次 UPDATE 都没有，
 *       所以 {@code password_hash} 在连续失败后必然不变（验收项之一）。</li>
 *   <li><b>失败计数只进日志</b>：达到 5 次不锁账号、不改响应（见 {@link LoginAttemptTracker}）。</li>
 * </ol>
 *
 * <p>为什么在"查不到用户"时还要跑一次 bcrypt：不跑的话，不存在的学号会在几毫秒内返回 401，
 * 存在的学号要 ~100ms（cost=10）。响应时间差本身就是账号枚举的旁路，把固定文案的防护绕过去了。
 * 因此用一个进程启动时随机生成、永不匹配的 {@code dummyHash} 占位，让两条路径的耗时同量级。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 登录失败的唯一对外文案（契约 §4.9）。 */
    static final String BAD_CREDENTIALS_MESSAGE = "学号或密码不正确";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptTracker loginAttempts;

    /**
     * 占位哈希：启动时随机生成，任何口令都不会匹配它。
     *
     * <p>不用写死常量：写死的字符串会被误认为"某个演示账号的口令"，也可能被误改成真实哈希；
     * 随机生成保证它永远只是"一次等价的 bcrypt 计算"。
     */
    private final String dummyHash;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, LoginAttemptTracker loginAttempts) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.loginAttempts = loginAttempts;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 登录：校验口令 → 建立会话（换 session id）→ 返回身份。
     *
     * <p>失败一律抛 {@code 401 + 10006 + "学号或密码不正确"}，与"未登录"共用同一个 code、
     * 不同 message（见 {@link BizException#withPublicMessage}）。
     */
    public UserResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String studentNo = request.getStudentNo();
        String clientIp = clientIp(httpRequest);

        User user = userMapper.selectByStudentNo(studentNo);
        boolean active = user != null && Boolean.TRUE.equals(user.getActive());
        String hashToCheck = active ? user.getPasswordHash() : dummyHash;
        boolean passwordMatches = matchesSafely(request.getPassword(), hashToCheck);

        if (!active || !passwordMatches) {
            int failures = loginAttempts.recordFailure(studentNo, clientIp);
            // 日志记原因（排障需要），响应不记原因（防枚举）。中文原因只进日志，不进 body。
            log.warn("登录失败 studentNo={} ip={} reason={} consecutiveFailures={}",
                    studentNo, clientIp, failureReason(user, active, passwordMatches), failures);
            throw BizException.withPublicMessage(ErrorCode.UNAUTHORIZED, BAD_CREDENTIALS_MESSAGE);
        }

        loginAttempts.clear(studentNo, clientIp);
        SessionAuth.signIn(httpRequest, new SessionUser(user.getId(), user.getStudentNo(), user.getDisplayName()));
        log.info("登录成功 userId={} ip={}", user.getId(), clientIp);
        return UserResponse.from(user);
    }

    /**
     * 登出：幂等。没有会话、会话已失效、重复调用都返回成功（契约 §4.10）。
     *
     * <p>清 Cookie 的响应头由 controller 拼（需要 {@code ResponseEntity}），本方法只管服务端状态。
     */
    public void logout(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        Long userId = session == null ? null : userIdOf(session);
        SessionAuth.signOut(httpRequest);
        log.info("登出 userId={} ip={}", userId, clientIp(httpRequest));
    }

    /** 当前用户：未登录 → 401 + 10006（由 {@link SessionAuth#requireUser} 抛）。 */
    public UserResponse currentUser(HttpServletRequest httpRequest) {
        return UserResponse.from(SessionAuth.requireUser(httpRequest));
    }

    /**
     * 跑一次 bcrypt 比较；哈希不是 bcrypt 形态（例如历史列里混进明文）时当"不匹配"处理并打 ERROR。
     *
     * <p>为什么吃 {@link IllegalArgumentException}：{@code BCryptPasswordEncoder.matches} 对非法哈希
     * 抛的是这个异常，不接就会变成 500 —— 而"这个账号的行坏了"不应该比"口令错"多给出任何信息。
     * 打 ERROR 是为了让运维在日志里看到数据问题，而不是让它静默。
     */
    private boolean matchesSafely(String rawPassword, String hash) {
        try {
            return passwordEncoder.matches(rawPassword, hash);
        } catch (IllegalArgumentException malformedHash) {
            log.error("t_user.password_hash 不是 bcrypt 形态，已按校验失败处理（数据问题，需修 seed/迁移脚本）");
            return false;
        }
    }

    /** 只进日志的失败原因分类；绝不进响应体。 */
    private static String failureReason(User user, boolean active, boolean passwordMatches) {
        if (user == null) {
            return "STUDENT_NO_NOT_FOUND";
        }
        if (!active) {
            return "USER_INACTIVE";
        }
        return passwordMatches ? "OK" : "PASSWORD_MISMATCH";
    }

    /**
     * 客户端 IP：只用连接对端地址，<b>不读 {@code X-Forwarded-For}</b>。
     *
     * <p>原因：该头由客户端自由填写，直接采信等于把"失败计数"的 key 交给攻击者（换头就绕过），
     * 也会污染日志。若将来部署在反向代理后，应由代理层覆盖该头、并在应用里显式配置"可信代理"后再读 ——
     * 那是配置项 + 代码改动，不是现在默认信任。
     */
    private static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    private static Long userIdOf(HttpSession session) {
        Object value = session.getAttribute(SessionAuth.SESSION_ATTRIBUTE);
        return value instanceof SessionUser user ? user.id() : null;
    }
}
