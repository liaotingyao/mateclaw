package vip.mate.wiki.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Wiki 模块增量 Schema 迁移
 * <p>
 * Wiki 基础表已在 schema.sql / schema-mysql.sql 中定义（随 DatabaseBootstrapRunner 初始化）。
 * 本类仅负责增量字段迁移，确保从旧版本升级时自动补齐新字段。
 * <p>
 * 如果表已由 schema.sql 创建（全新安装），这里的迁移是无副作用的空操作。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@Order(202)
@RequiredArgsConstructor
public class WikiSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        migrateKnowledgeBaseColumns();
    }

    /**
     * 增量字段迁移（兼容从 wiki 功能上线前的旧版本升级）
     */
    private void migrateKnowledgeBaseColumns() {
        try {
            // v1.1: 添加 source_directory 列（本地目录扫描功能）
            jdbcTemplate.execute("ALTER TABLE mate_wiki_knowledge_base ADD COLUMN IF NOT EXISTS source_directory VARCHAR(512)");
            log.debug("[WikiSchemaMigration] Incremental migration completed");
        } catch (Exception e) {
            // MySQL 不支持 ADD COLUMN IF NOT EXISTS，忽略已存在的错误
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Duplicate column") || msg.contains("already exists"))) {
                log.debug("[WikiSchemaMigration] Column source_directory already exists, skipping");
            } else {
                log.warn("[WikiSchemaMigration] Migration warning: {}", msg);
            }
        }
    }
}
