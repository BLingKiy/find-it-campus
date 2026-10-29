package com.findit.campus.review.postprocess;

/**
 * 发布后处理端口。实现必须<b>只发一次请求、不重试</b>：重试策略属于
 * {@code ReviewGateService}（它要落 attempts 并按指数退避），客户端重试会让
 * "尝试次数"与真实调用次数对不上。
 */
public interface PostprocessClient {

    PostprocessResult process(PostprocessCommand command);
}
