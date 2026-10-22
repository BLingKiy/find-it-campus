package com.findit.campus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动入口。
 *
 * <p><b>为什么手动写而不是用 MyBatis 的 {@code @MapperScan}</b>：按能力分包后，Mapper 分散在
 * {@code record/category/match} 各自包里。骨架阶段没有任何 Mapper 实现，3.1 落地时在对应能力的
 * mapper 接口上标 {@code @Mapper}（或在各自的 config 里 scan），避免顶层一把扫全包。
 *
 * <p>基线与开发约定见 {@code docs/architecture-baseline.md} §5.2 / §9。
 */
@SpringBootApplication
public class FindItCampusApplication {

    public static void main(String[] args) {
        SpringApplication.run(FindItCampusApplication.class, args);
    }
}
