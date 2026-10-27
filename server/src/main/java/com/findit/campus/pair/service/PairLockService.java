package com.findit.campus.pair.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 同一 pair 的跨入口互斥：MySQL 命名锁 {@code GET_LOCK('pair:{lostId}|{foundId}', 5)}（架构 §8.5）。
 *
 * <p><b>为什么必须自己拿一条连接</b>：MySQL 的命名锁是<b>会话级</b>的 —— 在连接 A 上
 * {@code GET_LOCK} 成功，只有连接 A 上的 {@code RELEASE_LOCK} 才有效，别的连接释放不掉。
 * 若用两次 Mapper 调用（Spring 每次可能给不同连接），就会出现"锁永远不释放"：
 * 连接归还池子后锁还挂在那个会话上，直到连接被回收 —— 这类故障在日志里只表现为
 * "某些 pair 再也不重算"，极难定位。所以这里直接 {@code DataSource.getConnection()}，
 * 锁的整个生命周期绑死在同一条连接上，{@link LockHandle#close()} 负责释放 + 归还。
 *
 * <p><b>代价（必须写明）</b>：锁的持有期覆盖一次 LLM 调用（最长 20 秒），
 * 期间占用一条连接。因此 {@code findit.pair.score.worker-threads} 必须明显小于
 * Hikari 的 {@code maximum-pool-size}（默认 10，默认 worker=2），否则打分线程会把连接池
 * 抽干，发布接口等连接 → P99 直接爆掉。这条约束写进了 {@code PairScoreProperties} 的注释，
 * 也是"发布链路不等待打分"必须同时满足的条件。
 *
 * <p>拿不到锁不是错误：说明另一个入口正在算同一对，直接跳过（下一轮扫描会补）。
 * 这比"等锁"更好 —— 等锁会把一个后台任务的尾延迟传给另一个后台任务。
 */
@Component
public class PairLockService {

    private static final Logger log = LoggerFactory.getLogger(PairLockService.class);

    /** 等锁上限（秒）：只是兜住"拿锁瞬间正好有人在释放"，不是长等待。 */
    private static final int LOCK_TIMEOUT_SECONDS = 5;

    private final DataSource dataSource;

    public PairLockService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 锁名只由两个 id 拼成，不含用户输入，长度远小于 64 字节上限。 */
    public static String lockName(long lostId, long foundId) {
        return "pair:" + lostId + "|" + foundId;
    }

    /**
     * 尝试加锁。
     *
     * @return 拿到锁时返回句柄（必须 try-with-resources）；拿不到返回 {@code null}
     * @throws IllegalStateException 连接/SQL 失败（这是基础设施故障，不是"跳过"）
     */
    public PairLock tryAcquire(long lostId, long foundId) {
        String name = lockName(lostId, foundId);
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
                statement.setString(1, name);
                statement.setInt(2, LOCK_TIMEOUT_SECONDS);
                try (ResultSet resultSet = statement.executeQuery()) {
                    boolean acquired = resultSet.next() && resultSet.getInt(1) == 1;
                    if (!acquired) {
                        connection.close();
                        return null;
                    }
                    return new LockHandle(connection, name);
                }
            }
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException("获取 pair 命名锁失败: " + name, e);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 归还失败的连接由连接池自己淘汰，这里不覆盖原始异常
        }
    }

    /** 锁句柄：{@code close()} 先 {@code RELEASE_LOCK} 再归还连接；两者都必须做。 */
    private static final class LockHandle implements PairLock {

        private final Connection connection;
        private final String name;
        private boolean released;

        private LockHandle(Connection connection, String name) {
            this.connection = connection;
            this.name = name;
        }

        @Override
        public void close() {
            if (released) {
                return;
            }
            released = true;
            try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, name);
                statement.executeQuery().close();
            } catch (SQLException e) {
                log.warn("释放 pair 命名锁失败 name={} cause={}", name, e.getMessage());
            } finally {
                closeQuietly(connection);
            }
        }
    }
}
