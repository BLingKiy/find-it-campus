package com.findit.campus.authprobe;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.findit.campus.common.api.ApiResponse;

/**
 * <b>测试专用</b>探针：占位一个 {@code GET /api/my/records} 路由，用来验证 B2 的登录拦截规则。
 *
 * <p>为什么需要它：B2 的验收项写着"无 cookie 访问 {@code /api/my/records} → 401"，但这三个接口属于
 * C1（我的启事 + pair 卡片），B2 交付时还没有这个路由。没有 handler 就没有拦截器链，
 * 直接打这个路径只会得到 404 —— 那是"接口还没做"，不是"拦截没生效"。
 * 用这个探针把"隐式的规则"变成"可执行的断言"，与仓库既有的
 * {@code com.findit.campus.validationprobe} 是同一手法。
 *
 * <p>C1 落地后本类连同测试一起删除，断言改打真接口。它<b>不会</b>进主源码，也不会出现在任何路由清单里。
 */
@RestController
@RequestMapping("/api/my")
public class MyRecordsProbeController {

    @GetMapping("/records")
    public ApiResponse<String> list() {
        return ApiResponse.success("probe");
    }
}
