package com.findit.campus.pair.service;

/**
 * 一对记录的命名锁句柄（{@link PairLockService} 的返回值）。
 *
 * <p>抽成接口而不是直接返回 {@code PairLockService.LockHandle} 的原因很实际：
 * 单测要验证"拿不到锁就跳过"，需要一个可以假造的锁；而持有真实锁需要一条数据库连接。
 * 一个只有 {@code close()} 的接口让调用方（{@code PairScoringService}）与"锁怎么实现"
 * 彻底解耦 —— 换成 Redis 锁时，改的是 {@code PairLockService}，不是业务代码。
 *
 * <p>语义：{@code close()} 必须幂等（{@code try-with-resources} 与显式 close 可能同时发生），
 * 且必须在<b>同一个连接/会话</b>上释放（MySQL 命名锁的会话级语义）。
 */
public interface PairLock extends AutoCloseable {

    /** 释放锁；重复调用无副作用。 */
    @Override
    void close();
}
