package com.findit.campus.validationprobe;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * 方法级参数校验的探针 controller —— <b>只存在于测试作用域，不进主代码</b>。
 *
 * <p>它的用途只有一个：给 {@link HandlerMethodValidationException} 这条出口造一个真实的触发点。
 * 类上<b>不标</b> {@code @Validated}，走的是 Spring 6.1+ MVC 内建方法校验路径，
 * 与生产里 controller 的装配方式一致，所以测出来的异常类型才是生产会遇到的那个。
 */
@RestController
@RequestMapping("/probe/method-validation")
public class MethodValidationProbeController {

    /** 两个参数可同时违规，用来验证 fieldErrors 返回<b>全部</b>字段而不是只取第一条。 */
    @GetMapping
    public String list(@RequestParam @Min(value = 1, message = "页码从 1 开始") int page,
                       @RequestParam @Max(value = 50, message = "每页最多 50 条") int size) {
        return "ok:" + page + ":" + size;
    }

    @GetMapping("/keyword")
    public String keyword(@RequestParam @Pattern(regexp = "[A-Za-z0-9_-]{2,20}", message = "关键词只允许字母数字下划线")
                          String keyword) {
        return "ok:" + keyword;
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable @Min(value = 1, message = "id 必须为正整数") long id) {
        return "ok:" + id;
    }
}
