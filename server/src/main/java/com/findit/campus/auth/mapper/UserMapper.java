package com.findit.campus.auth.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.findit.campus.auth.domain.User;

/**
 * {@code t_user} 的只读出口。
 *
 * <p>只提供 SELECT：B2 不建号、不改口令、不提供注册。运行期唯一的用户数据写入路径是
 * {@code db/seed.sql}（演示账号）与后续管理路径；给这个 Mapper 加 UPDATE 就等于给"改口令"开了入口，
 * 而改口令背后还有审计、复杂度校验、旧口令复核一串没做的功课。
 */
@Mapper
public interface UserMapper {

    /**
     * 按学号取用户。
     *
     * <p><b>不过滤 {@code is_active}</b>：停用与不存在在响应上必须无法区分（同一句 401 + 同一文案），
     * 但日志里应当能分清"这个账号被停用了"和"根本没这个人" —— 过滤在 SQL 里做会把两者压成同一个 {@code null}，
     * 排障时少一条关键线索。启用状态由 {@code AuthService} 判定。
     *
     * @param studentNo 登录名（唯一键 {@code uk_user_student_no}，等值命中）
     */
    User selectByStudentNo(@Param("studentNo") String studentNo);
}
