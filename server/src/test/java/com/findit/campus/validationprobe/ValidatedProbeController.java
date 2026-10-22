package com.findit.campus.validationprobe;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 方法级参数校验的另一种装配：类上标 {@code @Validated} —— <b>只存在于测试作用域</b>。
 *
 * <p>它校验的是同一件事，但约束由 {@code MethodValidationPostProcessor} 的 AOP 代理拦下，
 * 抛的是 {@link jakarta.validation.ConstraintViolationException} 而不是
 * {@link org.springframework.web.method.annotation.HandlerMethodValidationException}。
 * 留这个探针是为了把两条路径都钉住：换一种写法不会静默退化成 10005/500。
 */
@RestController
@Validated
@RequestMapping("/probe/validated")
public class ValidatedProbeController {

    @GetMapping
    public String list(@RequestParam @Min(value = 1, message = "页码从 1 开始") int page,
                       @RequestParam @Max(value = 50, message = "每页最多 50 条") int size) {
        return "ok:" + page + ":" + size;
    }
}
