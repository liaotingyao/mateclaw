package vip.mate.memory.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Memory 模块 Schema 迁移
 * <p>
 * 确保 mate_memory_recall 表存在（兼容已有部署）。
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
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS mate_memory_recall (
                        id                BIGINT       NOT NULL PRIMARY KEY,
                        agent_id          BIGINT       NOT NULL,
                        filename          VARCHAR(256) NOT NULL,
                        snippet_hash      VARCHAR(64),
                        snippet_preview   VARCHAR(512),
                        recall_count      INT          NOT NULL DEFAULT 0,
                        daily_count       INT          NOT NULL DEFAULT 0,
                        query_hashes      TEXT,
                        score             DOUBLE       NOT NULL DEFAULT 0.0,
                        last_recalled_at  DATETIME,
                        promoted          BOOLEAN      NOT NULL DEFAULT FALSE,
                        create_time       DATETIME     NOT NULL,
                        update_time       DATETIME     NOT NULL,
                        deleted           INT          NOT NULL DEFAULT 0
                    )
                    """);
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_memory_recall_agent ON mate_memory_recall(agent_id)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_memory_recall_agent_file ON mate_memory_recall(agent_id, filename)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_memory_recall_score ON mate_memory_recall(agent_id, score)");
            log.info("[MemorySchemaMigration] mate_memory_recall schema migration completed");
        } catch (Exception e) {
            log.warn("[MemorySchemaMigration] Migration failed (table may already exist): {}", e.getMessage());
        }
    }
}
