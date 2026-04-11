package vip.mate.tool.guard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.tool.guard.model.GuardCategory;
import vip.mate.tool.guard.model.GuardSeverity;
import vip.mate.tool.guard.model.ToolGuardConfigEntity;
import vip.mate.tool.guard.model.ToolGuardRuleEntity;
import vip.mate.tool.guard.repository.ToolGuardConfigMapper;
import vip.mate.tool.guard.repository.ToolGuardRuleMapper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 规则种子服务
 * <p>
 * 启动时完成三项工作：
 * <ol>
 *   <li>迁移旧版工具名（类名 → @Tool 方法名）+ 清理旧 legacy 规则</li>
 *   <li>按 rule_id 逐条 upsert 内置规则（不存在则插入，已存在则同步更新）</li>
 *   <li>迁移 guardedToolsJson 中的旧工具名</li>
 * </ol>
 */
@Slf4j
@Component
@Order(110) // Schema 由 Flyway 管理，在 Flyway 迁移完成后执行
@RequiredArgsConstructor
public class ToolGuardRuleSeedService implements ApplicationRunner {

    private final ToolGuardRuleMapper ruleMapper;
    private final ToolGuardConfigMapper configMapper;

    /** 旧类名 → 新 @Tool 方法名 */
    private static final Map<String, String> TOOL_NAME_RENAMES = Map.of(
            "ShellExecuteTool", "execute_shell_command",
            "WriteFileTool", "write_file",
            "EditFileTool", "edit_file"
    );

    /** 旧 SQL 种子中的 legacy rule_id，已被 Java 种子的新规则完全覆盖 */
    private static final Set<String> LEGACY_RULE_IDS = Set.of(
            "write_file_any",
            "edit_file_any",
            "shell_rm_approval",
            "shell_rm_rf_block",
            "shell_write_system_file",
            "shell_chmod_777"
    );

    @Override
    public void run(ApplicationArguments args) {
        migrateOldData();
        seedBuiltinRules();
    }

    // ==================== 旧数据迁移 ====================

    /**
     * 将 DB 中旧版数据统一迁移：
     * <ul>
     *   <li>rule 表旧工具名（类名 → @Tool 方法名）</li>
     *   <li>清理 legacy rule_id（旧 SQL 种子残留）</li>
     *   <li>config 表 guardedToolsJson 中的旧工具名</li>
     * </ul>
     */
    private void migrateOldData() {
        try {
            // 1. 迁移 rule 表旧工具名
            for (var entry : TOOL_NAME_RENAMES.entrySet()) {
                int updated = ruleMapper.update(null,
                        new LambdaUpdateWrapper<ToolGuardRuleEntity>()
                                .eq(ToolGuardRuleEntity::getToolName, entry.getKey())
                                .set(ToolGuardRuleEntity::getToolName, entry.getValue()));
                if (updated > 0) {
                    log.info("[RuleSeed] Migrated {} rules: {} -> {}", updated, entry.getKey(), entry.getValue());
                }
            }

            // 2. 清理旧 SQL 种子残留的 legacy 规则
            cleanupLegacyRules();

            // 3. 迁移 config 表 guardedToolsJson
            migrateGuardedToolsJson();
        } catch (Exception e) {
            log.warn("[RuleSeed] Migration failed (table may not exist): {}", e.getMessage());
        }
    }

    /**
     * 删除旧 SQL 种子中残留的 legacy builtin 规则。
     * 这些规则的 rule_id 与新 Java 种子不重叠，增量升级后会形成冗余重复。
     */
    private void cleanupLegacyRules() {
        for (String legacyId : LEGACY_RULE_IDS) {
            int deleted = ruleMapper.delete(
                    new LambdaQueryWrapper<ToolGuardRuleEntity>()
                            .eq(ToolGuardRuleEntity::getRuleId, legacyId)
                            .eq(ToolGuardRuleEntity::getBuiltin, true));
            if (deleted > 0) {
                log.info("[RuleSeed] Removed legacy rule: {}", legacyId);
            }
        }
    }

    private void migrateGuardedToolsJson() {
        try {
            List<ToolGuardConfigEntity> configs = configMapper.selectList(null);
            for (ToolGuardConfigEntity config : configs) {
                String json = config.getGuardedToolsJson();
                if (json == null || json.isBlank()) continue;

                String updated = json;
                for (var entry : TOOL_NAME_RENAMES.entrySet()) {
                    updated = updated.replace("\"" + entry.getKey() + "\"", "\"" + entry.getValue() + "\"");
                }
                if (!updated.equals(json)) {
                    config.setGuardedToolsJson(updated);
                    configMapper.updateById(config);
                    log.info("[RuleSeed] Migrated guardedToolsJson: {} -> {}", json, updated);
                }
            }
        } catch (Exception e) {
            log.debug("[RuleSeed] guardedToolsJson migration skipped: {}", e.getMessage());
        }
    }

    // ==================== 规则种子 ====================

    /**
     * 按 rule_id 逐条 upsert 内置规则：
     * <ul>
     *   <li>不存在 → 插入</li>
     *   <li>已存在 → 同步更新 pattern / severity / decision / priority / toolName 等字段</li>
     * </ul>
     * 这样后续版本修正了 regex 或 severity，已有部署也能在重启时自动拿到更新。
     */
    void seedBuiltinRules() {
        try {
            // 加载已有 builtin 规则（按 rule_id 索引）
            List<ToolGuardRuleEntity> existingList = ruleMapper.selectList(
                    new LambdaQueryWrapper<ToolGuardRuleEntity>()
                            .eq(ToolGuardRuleEntity::getBuiltin, true));
            Map<String, ToolGuardRuleEntity> existingMap = existingList.stream()
                    .collect(Collectors.toMap(ToolGuardRuleEntity::getRuleId, e -> e, (a, b) -> a));

            List<ToolGuardRuleEntity> rules = buildBuiltinRules();
            int inserted = 0;
            int updated = 0;
            int unchanged = 0;

            for (ToolGuardRuleEntity rule : rules) {
                ToolGuardRuleEntity existing = existingMap.get(rule.getRuleId());
                if (existing == null) {
                    // 新规则 → 插入
                    try {
                        ruleMapper.insert(rule);
                        inserted++;
                    } catch (Exception e) {
                        log.debug("[RuleSeed] Rule {} insert failed: {}", rule.getRuleId(), e.getMessage());
                    }
                } else if (needsUpdate(existing, rule)) {
                    // 已存在但内容有变化 → 更新
                    ruleMapper.update(null,
                            new LambdaUpdateWrapper<ToolGuardRuleEntity>()
                                    .eq(ToolGuardRuleEntity::getRuleId, rule.getRuleId())
                                    .set(ToolGuardRuleEntity::getName, rule.getName())
                                    .set(ToolGuardRuleEntity::getDescription, rule.getDescription())
                                    .set(ToolGuardRuleEntity::getPattern, rule.getPattern())
                                    .set(ToolGuardRuleEntity::getSeverity, rule.getSeverity())
                                    .set(ToolGuardRuleEntity::getCategory, rule.getCategory())
                                    .set(ToolGuardRuleEntity::getDecision, rule.getDecision())
                                    .set(ToolGuardRuleEntity::getToolName, rule.getToolName())
                                    .set(ToolGuardRuleEntity::getRemediation, rule.getRemediation())
                                    .set(ToolGuardRuleEntity::getPriority, rule.getPriority()));
                    updated++;
                } else {
                    unchanged++;
                }
            }
            log.info("[RuleSeed] Builtin rules: {} inserted, {} updated, {} unchanged",
                    inserted, updated, unchanged);
        } catch (Exception e) {
            log.warn("[RuleSeed] Failed to seed rules (table may not exist): {}", e.getMessage());
        }
    }

    /**
     * 判断已有 builtin 规则是否需要更新（任一核心字段有变化即需要）
     */
    private boolean needsUpdate(ToolGuardRuleEntity existing, ToolGuardRuleEntity expected) {
        return !Objects.equals(existing.getPattern(), expected.getPattern())
                || !Objects.equals(existing.getSeverity(), expected.getSeverity())
                || !Objects.equals(existing.getCategory(), expected.getCategory())
                || !Objects.equals(existing.getDecision(), expected.getDecision())
                || !Objects.equals(existing.getToolName(), expected.getToolName())
                || !Objects.equals(existing.getPriority(), expected.getPriority())
                || !Objects.equals(existing.getName(), expected.getName())
                || !Objects.equals(existing.getRemediation(), expected.getRemediation());
    }

    private List<ToolGuardRuleEntity> buildBuiltinRules() {
        List<ToolGuardRuleEntity> rules = new ArrayList<>();

        // === CRITICAL Shell Rules ===
        rules.add(rule("SHELL_RM_RF_ROOT", "递归强制删除根目录", "rm\\s+-(rf|fr)\\s+/\\s*$",
                GuardSeverity.CRITICAL, GuardCategory.COMMAND_INJECTION, "BLOCK",
                "execute_shell_command", "请指定具体目录路径而非根目录", 200));

        rules.add(rule("SHELL_MKFS", "文件系统格式化", "mkfs\\b",
                GuardSeverity.CRITICAL, GuardCategory.COMMAND_INJECTION, "BLOCK",
                "execute_shell_command", "确认目标设备后手动执行", 200));

        rules.add(rule("SHELL_DD_DEV", "直接磁盘写入", "dd\\s+if=.+of=/dev/",
                GuardSeverity.CRITICAL, GuardCategory.COMMAND_INJECTION, "BLOCK",
                "execute_shell_command", "确认目标设备后手动执行", 200));

        rules.add(rule("SHELL_KILL_INIT", "杀死 init/systemd", "\\bkill\\s+-9\\s+1\\b",
                GuardSeverity.CRITICAL, GuardCategory.RESOURCE_ABUSE, "BLOCK",
                "execute_shell_command", "使用 systemctl 管理服务", 200));

        rules.add(rule("SHELL_CURL_PIPE_SH", "管道下载执行 (curl)", "curl.*\\|\\s*(sh|bash|zsh)",
                GuardSeverity.CRITICAL, GuardCategory.CODE_EXECUTION, "BLOCK",
                "execute_shell_command", "先下载文件审查内容再执行", 200));

        rules.add(rule("SHELL_WGET_PIPE_SH", "管道下载执行 (wget)", "wget.*\\|\\s*(sh|bash|zsh)",
                GuardSeverity.CRITICAL, GuardCategory.CODE_EXECUTION, "BLOCK",
                "execute_shell_command", "先下载文件审查内容再执行", 200));

        rules.add(rule("SHELL_FORK_BOMB", "Fork Bomb", ":\\(\\)\\s*\\{\\s*:\\|:\\s*&\\s*\\}\\s*;\\s*:",
                GuardSeverity.CRITICAL, GuardCategory.RESOURCE_ABUSE, "BLOCK",
                "execute_shell_command", "此命令无正当用途", 200));

        rules.add(rule("SHELL_REVERSE_SHELL", "反向 Shell", "(/dev/tcp|\\bnc\\s+-e\\b|\\bncat\\s+-e\\b|\\bsocat\\s+EXEC:)",
                GuardSeverity.CRITICAL, GuardCategory.NETWORK_ABUSE, "BLOCK",
                "execute_shell_command", "此命令可能被用于远程控制", 200));

        // === HIGH Shell Rules ===
        rules.add(rule("SHELL_RM", "rm 删除命令", "(^|[;&|]|\\s)rm\\s",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                "execute_shell_command", "请确认要删除的文件列表，考虑使用 trash 替代 rm", 150));

        rules.add(rule("SHELL_RM_RF", "递归强制删除", "rm\\s+-(rf|fr)",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                "execute_shell_command", "使用 rm -ri 或指定具体文件", 150));

        rules.add(rule("SHELL_RM_ROOT", "从根路径删除", "rm\\s+/",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                "execute_shell_command", "请指定具体路径", 150));

        rules.add(rule("SHELL_RMDIR_ROOT", "从根路径删除目录", "rmdir\\s+/",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                "execute_shell_command", "请指定具体路径", 150));

        rules.add(rule("SHELL_SQL_DROP", "SQL DROP 操作", "DROP\\s+(TABLE|DATABASE|INDEX|VIEW|SCHEMA)",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                null, "请先备份数据再执行", 150));

        rules.add(rule("SHELL_SQL_TRUNCATE", "SQL TRUNCATE 操作", "TRUNCATE\\s+TABLE",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                null, "请先备份数据再执行", 150));

        rules.add(rule("SHELL_SQL_DELETE_ALL", "SQL 无条件 DELETE", "DELETE\\s+FROM\\s+\\w+\\s*;",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                null, "请添加 WHERE 条件", 150));

        rules.add(rule("SHELL_SQL_ALTER_DROP", "SQL ALTER TABLE DROP", "ALTER\\s+TABLE\\s+\\w+\\s+DROP",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                null, "请先备份数据再执行", 150));

        rules.add(rule("SHELL_SHUTDOWN", "系统关机", "\\bshutdown\\b",
                GuardSeverity.HIGH, GuardCategory.RESOURCE_ABUSE, "NEEDS_APPROVAL",
                "execute_shell_command", "请确认是否需要关机", 150));

        rules.add(rule("SHELL_REBOOT", "系统重启", "\\breboot\\b",
                GuardSeverity.HIGH, GuardCategory.RESOURCE_ABUSE, "NEEDS_APPROVAL",
                "execute_shell_command", "请确认是否需要重启", 150));

        rules.add(rule("SHELL_CHMOD_777", "过度宽松权限", "chmod\\s+777",
                GuardSeverity.HIGH, GuardCategory.PRIVILEGE_ESCALATION, "NEEDS_APPROVAL",
                "execute_shell_command", "使用最小必要权限", 150));

        rules.add(rule("SHELL_EVAL", "动态代码执行", "eval\\s*\\(",
                GuardSeverity.HIGH, GuardCategory.CODE_EXECUTION, "NEEDS_APPROVAL",
                null, "避免使用 eval", 150));

        rules.add(rule("SHELL_GIT_FORCE_PUSH", "Git 强制推送", "git\\s+push\\s+.*--force",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                "execute_shell_command", "使用 --force-with-lease", 150));

        rules.add(rule("SHELL_GIT_RESET_HARD", "Git 硬重置", "git\\s+reset\\s+--hard",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                "execute_shell_command", "先用 git stash", 150));

        rules.add(rule("SHELL_CRONTAB", "定时任务修改", "\\bcrontab\\b",
                GuardSeverity.HIGH, GuardCategory.PRIVILEGE_ESCALATION, "NEEDS_APPROVAL",
                "execute_shell_command", "请确认定时任务内容", 150));

        rules.add(rule("SHELL_AUTHORIZED_KEYS", "SSH 密钥修改", "authorized_keys",
                GuardSeverity.HIGH, GuardCategory.PRIVILEGE_ESCALATION, "NEEDS_APPROVAL",
                null, "请确认 SSH 密钥变更", 150));

        rules.add(rule("SHELL_SUDOERS", "sudo 权限修改", "/etc/sudoers",
                GuardSeverity.HIGH, GuardCategory.PRIVILEGE_ESCALATION, "NEEDS_APPROVAL",
                null, "请使用 visudo", 150));

        rules.add(rule("SHELL_OBFUSCATED_EXEC", "混淆代码执行", "base64\\s+-d.*\\|\\s*(bash|sh)",
                GuardSeverity.HIGH, GuardCategory.CODE_EXECUTION, "NEEDS_APPROVAL",
                "execute_shell_command", "先解码查看内容再执行", 150));

        // === Credential Rules ===
        rules.add(rule("CRED_PASSWORD_ASSIGN", "凭据信息暴露", "(password|secret|api[_-]?key|token)\\s*=\\s*['\"]?\\S{8,}",
                GuardSeverity.HIGH, GuardCategory.CREDENTIAL_EXPOSURE, "NEEDS_APPROVAL",
                null, "使用环境变量或密钥管理服务", 140));

        rules.add(rule("CRED_AWS_KEY", "AWS Access Key 泄露", "AKIA[0-9A-Z]{16}",
                GuardSeverity.HIGH, GuardCategory.CREDENTIAL_EXPOSURE, "NEEDS_APPROVAL",
                null, "使用 IAM Role 或 AWS Secrets Manager", 140));

        rules.add(rule("CRED_PRIVATE_KEY", "私钥泄露", "-----BEGIN\\s+(RSA\\s+)?PRIVATE\\s+KEY-----",
                GuardSeverity.HIGH, GuardCategory.CREDENTIAL_EXPOSURE, "BLOCK",
                null, "请勿在参数中传递私钥", 140));

        return rules;
    }

    private ToolGuardRuleEntity rule(String ruleId, String name, String pattern,
                                     GuardSeverity severity, GuardCategory category,
                                     String decision, String toolName, String remediation,
                                     int priority) {
        ToolGuardRuleEntity entity = new ToolGuardRuleEntity();
        entity.setRuleId(ruleId);
        entity.setName(name);
        entity.setDescription(name);
        entity.setPattern(pattern);
        entity.setSeverity(severity.name());
        entity.setCategory(category.name());
        entity.setDecision(decision);
        entity.setToolName(toolName);
        entity.setRemediation(remediation);
        entity.setBuiltin(true);
        entity.setEnabled(true);
        entity.setPriority(priority);
        return entity;
    }
}
