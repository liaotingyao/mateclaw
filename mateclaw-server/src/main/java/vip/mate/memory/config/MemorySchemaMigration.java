package vip.mate.memory.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Memory 模块增量 Schema 迁移
 * <p>
 * 基础表 mate_memory_recall 已在 schema.sql / schema-mysql.sql 中定义。
 * 本类仅负责增量索引/字段迁移，确保从旧版本升级时自动补齐。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@Order(201)
@RequiredArgsConstructor
public class MemorySchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        // 增量索引补齐（v1.1 新增的复合索引，旧版本可能没有）
        safeExecute("CREATE INDEX IF NOT EXISTS idx_memory_recall_candidates ON mate_memory_recall(agent_id, promoted, deleted)");

        // Session Search: MySQL FULLTEXT index on mate_message.content
        if (isMySql()) {
            safeExecute("ALTER TABLE mate_message ADD FULLTEXT INDEX ft_msg_content (content)");
            log.info("[MemorySchemaMigration] MySQL FULLTEXT index on mate_message.content created (or already exists)");
        }

        log.debug("[MemorySchemaMigration] Incremental migration completed");
    }

    private boolean isMySql() {
        try {
            String url = jdbcTemplate.getDataSource().getConnection().getMetaData().getURL();
            return url != null && url.contains("mysql");
        } catch (Exception e) {
            return false;
        }
    }

    private void safeExecute(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            log.debug("[MemorySchemaMigration] Migration skipped (may already exist): {}", e.getMessage());
        }
    }
}
