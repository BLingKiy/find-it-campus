package com.findit.campus.pair.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.findit.campus.auth.session.SessionAuth;
import com.findit.campus.common.api.ApiResponse;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.pair.service.AuditMeta;
import com.findit.campus.pair.service.PairActionService;
import com.findit.campus.pair.service.PairContactService;
import com.findit.campus.pair.service.PairUnlockService;
import com.findit.campus.pair.web.dto.ContactUnlockRequest;
import com.findit.campus.pair.web.dto.ContactUnlockResponse;
import com.findit.campus.pair.web.dto.ContactView;
import com.findit.campus.pair.web.dto.PairActionRequest;
import com.findit.campus.pair.web.dto.PairActionResponse;
import com.findit.campus.pair.web.dto.PairActionResult;

import jakarta.servlet.http.HttpServletRequest;

/**
 * pair 上下文的三条 HTTP 入口（契约 §4.13 / §4.14 / §4.15）。
 *
 * <p><b>path id 用 String 接、手工解析</b>：{@code /api/pairs/abc/contact} 在契约里是
 * 404/20004（「不存在或超界 id，不 500」），而 Spring 的 {@code @PathVariable long} 会在
 * {@code MethodArgumentTypeMismatchException} 处被全局处理器翻译成 400/10001。
 * 多写一个 parse 方法，换掉一条跨接口不一致的行为。
 *
 * <p>登录门禁：{@code /api/pairs} 已在 {@code AuthPaths.LOGIN_REQUIRED_READS}，匿名在 Filter
 * 就拿到 401；controller 里 {@link SessionAuth#requireUser} 是第二层（防绕过 Filter 的转发/直调）。
 */
@RestController
@RequestMapping("/api/pairs")
public class PairController {

    private final PairContactService contactService;
    private final PairUnlockService unlockService;
    private final PairActionService actionService;

    public PairController(PairContactService contactService,
                          PairUnlockService unlockService,
                          PairActionService actionService) {
        this.contactService = contactService;
        this.unlockService = unlockService;
        this.actionService = actionService;
    }

    /** 查看对方联系方式：200 成功体里也可能 {@code available=false}（「对方尚未公开」不是错误）。 */
    @GetMapping("/{id}/contact")
    public ResponseEntity<ApiResponse<ContactView>> contact(@PathVariable("id") String id,
                                                            HttpServletRequest request) {
        long pairId = parsePairId(id);
        long userId = SessionAuth.requireUser(request).id();
        ContactView view = contactService.view(pairId, userId, AuditMeta.from(request));
        return ResponseEntity.ok()
                // 每次查看都写审计，不能被任何中间层缓存
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.success(view));
    }

    /** 本端授权公开 / 撤回；body 可省略（等价于 PUBLISH 且无附言）。 */
    @PostMapping("/{id}/contact-unlock")
    public ApiResponse<ContactUnlockResponse> unlock(@PathVariable("id") String id,
                                                     @RequestBody(required = false) ContactUnlockRequest body,
                                                     HttpServletRequest request) {
        long pairId = parsePairId(id);
        long userId = SessionAuth.requireUser(request).id();
        ContactUnlockRequest effective = body == null ? new ContactUnlockRequest() : body;
        return ApiResponse.success(unlockService.unlock(pairId, userId, effective));
    }

    /** 联系 / 确认 / 忽略；成功文案放在统一响应体的 message。 */
    @PatchMapping("/{id}")
    public ApiResponse<PairActionResponse> act(@PathVariable("id") String id,
                                               @RequestBody PairActionRequest body,
                                               HttpServletRequest request) {
        long pairId = parsePairId(id);
        long userId = SessionAuth.requireUser(request).id();
        PairActionResult result = actionService.act(pairId, userId, body);
        return ApiResponse.success(result.data(), result.message());
    }

    private static long parsePairId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.of(ErrorCode.PAIR_NOT_FOUND);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException notANumber) {
            // 契约：不存在或非数字/超界 id → 404/20004，不 500
            throw BizException.of(ErrorCode.PAIR_NOT_FOUND);
        }
    }
}
