package vip.mate.approval.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 审批表增量 Schema 迁移
 * <p>
 * 基础表 mate_tool_approval 已在 schema.sql / schema-mysql.sql 中定义。
 * 本类仅负责增量字段/索引迁移，确保从旧版本升级时自动补齐。
 * <p>
 * 当前无需增量迁移，保留框架以备未来扩展。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@Order(50)
@RequiredArgsConstructor
public class ApprovalSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        // 当前无增量迁移。未来新增字段时在此添加 safeAddColumn 调用。
        log.debug("[ApprovalSchemaMigration] Incremental migration completed (no-op)");
    }
}
