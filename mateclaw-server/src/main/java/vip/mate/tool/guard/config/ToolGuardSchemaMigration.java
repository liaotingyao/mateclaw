package vip.mate.tool.guard.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ToolGuard 增量 Schema 迁移
 * <p>
 * 基础表 mate_tool_guard_rule / config / audit_log 已在 schema.sql / schema-mysql.sql 中定义。
 * 本类仅负责增量字段迁移，确保从旧版本升级时自动补齐新列。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class ToolGuardSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        migrateGuardConfigAuditColumns();
    }

    /**
     * 为 mate_tool_guard_config 补充审计配置列（向已有表兼容迁移）
     */
    private void migrateGuardConfigAuditColumns() {
        safeAddColumn("mate_tool_guard_config", "audit_enabled", "BOOLEAN NOT NULL DEFAULT TRUE");
        safeAddColumn("mate_tool_guard_config", "audit_min_severity", "VARCHAR(16) NOT NULL DEFAULT 'INFO'");
        safeAddColumn("mate_tool_guard_config", "audit_retention_days", "INT NOT NULL DEFAULT 90");
        log.debug("[ToolGuardSchemaMigration] Incremental migration completed");
    }

    private void safeAddColumn(String table, String column, String definition) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table.toUpperCase(), column.toUpperCase());
            if (count != null && count > 0) {
                return;
            }
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (Exception e) {
            log.debug("[ToolGuardSchemaMigration] Column migration skipped: {}", e.getMessage());
        }
    }
}
