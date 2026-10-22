package com.findit.campus.match.engine.local;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.findit.campus.category.domain.Category;
import com.findit.campus.category.mapper.CategoryMapper;
import com.findit.campus.match.engine.MatchCandidateSource;
import com.findit.campus.record.domain.RecordType;

/**
 * 测试夹具：直接解析仓库里的 {@code db/seed.sql}，而不是在测试里手抄一份数据。
 *
 * <p>理由：S1–N4 的样例集就写在 {@code seed.sql} 末尾，手抄会漂移；测试读同一个文件，
 * "样例集"才有资格叫验收证据。解析失败/条数不符直接 fail，不让夹具悄悄退化成空池。
 */
final class SeedSamples {

    private static final Path SEED = locateSeed();
    private static final Pattern RECORD = Pattern.compile(
            "\\((\\d+), '(LOST|FOUND)', '(PENDING|CLAIMED|CLOSED)', '([^']*)',\\s*'([^']*)',"
                    + "\\s*(NULL|\\d+), '([^']*)'");
    private static final Pattern CATEGORY = Pattern.compile(
            "\\((\\d+), '([A-Z]+)',\\s*'([^']*)',\\s*(?:NULL|'([^']*)'),\\s*\\d+, ([01])\\)");

    private static final List<SeedRecord> RECORDS = parseRecords();
    private static final List<Category> CATEGORIES = parseCategories();

    private SeedSamples() {
    }

    static List<SeedRecord> records() {
        return RECORDS;
    }

    static List<Category> categories() {
        return CATEGORIES;
    }

    /** 与 RecordCandidatePoolAdapter 同口径：未删除 + PENDING（seed 里 25 号是逻辑删除）。 */
    static List<MatchCandidateSource> pool(String type) {
        List<MatchCandidateSource> pool = new ArrayList<>();
        for (SeedRecord record : RECORDS) {
            if (!"PENDING".equals(record.status()) || record.id() == 25L) {
                continue;
            }
            if (type != null && !type.equals(record.type())) {
                continue;
            }
            pool.add(new MatchCandidateSource(record.id(), record.title(), record.description(),
                    record.categoryId(), record.location()));
        }
        return pool;
    }

    static MatchCandidateSource source(long id) {
        return RECORDS.stream()
                .filter(record -> record.id() == id)
                .findFirst()
                .map(record -> new MatchCandidateSource(record.id(), record.title(), record.description(),
                        record.categoryId(), record.location()))
                .orElseThrow(() -> new IllegalArgumentException("seed 中不存在记录 " + id));
    }

    /** 只读词表桩：引擎只会调 {@code selectAll(true)}，这里按 is_active 过滤。 */
    static CategoryMapper categoryMapper() {
        return new CategoryMapper() {
            @Override
            public List<Category> selectAll(boolean onlyActive) {
                return CATEGORIES.stream()
                        .filter(category -> !onlyActive || Boolean.TRUE.equals(category.getActive()))
                        .toList();
            }

            @Override
            public Category selectById(long id) {
                return CATEGORIES.stream()
                        .filter(category -> category.getId() == id)
                        .findFirst()
                        .orElse(null);
            }
        };
    }

    static CategoryMapper failingCategoryMapper() {
        return new CategoryMapper() {
            @Override
            public List<Category> selectAll(boolean onlyActive) {
                throw new IllegalStateException("t_category unavailable (test stub)");
            }

            @Override
            public Category selectById(long id) {
                throw new IllegalStateException("t_category unavailable (test stub)");
            }
        };
    }

    static RecordType typeOf(String type) {
        return type == null ? null : RecordType.valueOf(type);
    }

    // ------------------------------------------------------------------ 解析

    private static Path locateSeed() {
        Path fromModule = Path.of("..", "db", "seed.sql");
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        Path fromRoot = Path.of("db", "seed.sql");
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        throw new IllegalStateException("找不到 db/seed.sql（当前目录 " + Path.of("").toAbsolutePath() + "）");
    }

    private static List<SeedRecord> parseRecords() {
        String sql = read();
        List<SeedRecord> records = new ArrayList<>();
        Matcher matcher = RECORD.matcher(sql);
        while (matcher.find()) {
            records.add(new SeedRecord(
                    Long.parseLong(matcher.group(1)),
                    matcher.group(2),
                    matcher.group(3),
                    matcher.group(4),
                    matcher.group(5),
                    "NULL".equals(matcher.group(6)) ? null : Long.parseLong(matcher.group(6)),
                    matcher.group(7)));
        }
        if (records.size() != 24) {
            throw new IllegalStateException("db/seed.sql 解析出 " + records.size() + " 条记录，期望 24 条");
        }
        return List.copyOf(records);
    }

    private static List<Category> parseCategories() {
        String sql = read();
        List<Category> categories = new ArrayList<>();
        Matcher matcher = CATEGORY.matcher(sql);
        while (matcher.find()) {
            Category category = new Category();
            category.setId(Long.parseLong(matcher.group(1)));
            category.setCode(matcher.group(2));
            category.setName(matcher.group(3));
            category.setKeywords(matcher.group(4));
            category.setActive("1".equals(matcher.group(5)));
            categories.add(category);
        }
        if (categories.size() != 9) {
            throw new IllegalStateException("db/seed.sql 解析出 " + categories.size() + " 个分类，期望 9 个");
        }
        return List.copyOf(categories);
    }

    private static String read() {
        try {
            return Files.readString(SEED, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + SEED.toAbsolutePath() + " 失败", e);
        }
    }

    record SeedRecord(long id, String type, String status, String title, String description,
                      Long categoryId, String location) {
    }
}
