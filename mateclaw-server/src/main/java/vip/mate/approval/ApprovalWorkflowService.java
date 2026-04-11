package vip.mate.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import vip.mate.approval.model.ToolApprovalEntity;
import vip.mate.approval.repository.ToolApprovalMapper;
import vip.mate.tool.guard.model.GuardEvaluation;
import vip.mate.tool.guard.model.GuardFinding;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 审批工作流服务（write-through: 内存 + DB 双写）
 * <p>
 * 在现有 ApprovalService（内存层）之上，增加 DB 持久化。
 * 所有写操作先走 ApprovalService，再写 DB。
 * 启动时从 DB 恢复 PENDING 状态到内存。
 */
@Slf4j
@Service
@Order(55) // Schema 由 Flyway 管理，在 Flyway 迁移完成后执行
@RequiredArgsConstructor
public class ApprovalWorkflowService implements ApplicationRunner {

    private final ApprovalService approvalService;
    private final ToolApprovalMapper approvalMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        recoverFromDb();
    }

    /**
     * 启动时从 DB 恢复 PENDING 审批到内存
     */
    void recoverFromDb() {
        try {
            List<ToolApprovalEntity> pendingRecords = approvalMapper.selectList(
                    new LambdaQueryWrapper<ToolApprovalEntity>()
                            .eq(ToolApprovalEntity::getStatus, "PENDING")
                            .orderByAsc(ToolApprovalEntity::getCreatedAt)
            );

            int recovered = 0;
            for (ToolApprovalEntity entity : pendingRecords) {
                // 检查是否已过期（30 分钟）
                if (entity.getCreatedAt() != null) {
                    Instant createdAt = entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
                    if (Instant.now().minusSeconds(1800).isAfter(createdAt)) {
                        // 已过期，更新 DB 状态
                        entity.setStatus("TIMEOUT");
                        entity.setResolvedAt(LocalDateTime.now());
                        approvalMapper.updateById(entity);
                        continue;
                    }
                }

                // 恢复到内存
                String pendingId = approvalService.createPending(
                        entity.getConversationId(),
                        entity.getUserId(),
                        entity.getToolName(),
                        entity.getToolArguments(),
                        entity.getSummary(),
                        entity.getToolCallPayload(),
                        entity.getSiblingToolCalls(),
                        entity.getAgentId()
                );

                // 修正内存中的 pendingId 以匹配 DB
                // 由于 ApprovalService.createPending 会生成新 ID，我们需要取消它并使用原始 ID
                approvalService.cancelStalePending(entity.getConversationId(), null);
                pendingId = approvalService.createPending(
                        entity.getConversationId(),
                        entity.getUserId(),
                        entity.getToolName(),
                        entity.getToolArguments(),
                        entity.getSummary(),
                        entity.getToolCallPayload(),
                        entity.getSiblingToolCalls(),
                        entity.getAgentId()
                );

                recovered++;
            }

            if (recovered > 0) {
                log.info("[ApprovalWorkflow] Recovered {} pending approvals from DB", recovered);
            }
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] Failed to recover from DB (table may not exist yet): {}", e.getMessage());
        }
    }

    /**
     * 创建待审批记录（增强版，含 GuardEvaluation）
     */
    public String createPending(String conversationId, String userId,
                                String toolName, String toolArguments, String reason,
                                String toolCallPayload, String siblingToolCalls, String agentId,
                                GuardEvaluation evaluation) {
        // 1. 内存层
        String pendingId = approvalService.createPending(
                conversationId, userId, toolName, toolArguments, reason,
                toolCallPayload, siblingToolCalls, agentId);

        // 2. 增强内存记录
        approvalService.getPending(pendingId).ifPresent(pending -> {
            if (evaluation != null) {
                pending.setFindingsJson(serializeFindings(evaluation.findings()));
                pending.setMaxSeverity(evaluation.maxSeverity() != null ? evaluation.maxSeverity().name() : null);
                pending.setSummary(evaluation.summary());
            }
        });

        // 3. DB 层
        persistToDb(pendingId, conversationId, userId, toolName, toolArguments,
                toolCallPayload, siblingToolCalls, agentId, evaluation);

        return pendingId;
    }

    /**
     * 创建待审批记录（基础版，向后兼容）
     */
    public String createPending(String conversationId, String userId,
                                String toolName, String toolArguments, String reason,
                                String toolCallPayload, String siblingToolCalls, String agentId) {
        return createPending(conversationId, userId, toolName, toolArguments, reason,
                toolCallPayload, siblingToolCalls, agentId, null);
    }

    /**
     * 解决审批
     */
    public void resolve(String pendingId, String userId, String decision) {
        approvalService.resolve(pendingId, userId, decision);
        updateDbStatus(pendingId, decision.toUpperCase(), userId);
    }

    /**
     * 原子解决+消费
     */
    public PendingApproval resolveAndConsume(String pendingId, String userId) {
        PendingApproval consumed = approvalService.resolveAndConsume(pendingId, userId);
        if (consumed != null) {
            updateDbStatus(pendingId, "CONSUMED", userId);
        }
        return consumed;
    }

    /**
     * 消费已批准记录
     */
    public PendingApproval consumeApproved(String conversationId, String toolName) {
        PendingApproval consumed = approvalService.consumeApproved(conversationId, toolName);
        if (consumed != null) {
            updateDbStatus(consumed.getPendingId(), "CONSUMED", null);
        }
        return consumed;
    }

    /**
     * 取消过期 pending
     */
    public void cancelStalePending(String conversationId, String excludePendingId) {
        approvalService.cancelStalePending(conversationId, excludePendingId);

        try {
            approvalMapper.update(null, new LambdaUpdateWrapper<ToolApprovalEntity>()
                    .eq(ToolApprovalEntity::getConversationId, conversationId)
                    .eq(ToolApprovalEntity::getStatus, "PENDING")
                    .ne(excludePendingId != null, ToolApprovalEntity::getPendingId, excludePendingId)
                    .set(ToolApprovalEntity::getStatus, "SUPERSEDED")
                    .set(ToolApprovalEntity::getResolvedAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] Failed to cancel stale in DB: {}", e.getMessage());
        }
    }

    /**
     * 代理查询方法
     */
    public PendingApproval findPendingByConversation(String conversationId) {
        return approvalService.findPendingByConversation(conversationId);
    }

    public List<Map<String, Object>> getPendingByConversation(String conversationId) {
        return approvalService.getPendingByConversation(conversationId);
    }

    // ==================== 内部方法 ====================

    private void persistToDb(String pendingId, String conversationId, String userId,
                             String toolName, String toolArguments,
                             String toolCallPayload, String siblingToolCalls, String agentId,
                             GuardEvaluation evaluation) {
        try {
            ToolApprovalEntity entity = new ToolApprovalEntity();
            entity.setPendingId(pendingId);
            entity.setConversationId(conversationId);
            entity.setUserId(userId);
            entity.setAgentId(agentId);
            entity.setToolName(toolName);
            entity.setToolArguments(toolArguments);
            entity.setToolCallPayload(toolCallPayload);
            entity.setSiblingToolCalls(siblingToolCalls);
            entity.setStatus("PENDING");
            entity.setCreatedAt(LocalDateTime.now());
            entity.setExpireAt(LocalDateTime.now().plusMinutes(30));

            if (evaluation != null) {
                entity.setFindingsJson(serializeFindings(evaluation.findings()));
                entity.setMaxSeverity(evaluation.maxSeverity() != null ? evaluation.maxSeverity().name() : null);
                entity.setSummary(evaluation.summary());

                if (toolCallPayload != null) {
                    entity.setToolCallHash(String.valueOf(toolCallPayload.hashCode()));
                }
            }

            approvalMapper.insert(entity);
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] Failed to persist approval to DB: {}", e.getMessage());
        }
    }

    private void updateDbStatus(String pendingId, String status, String resolvedBy) {
        try {
            LambdaUpdateWrapper<ToolApprovalEntity> wrapper = new LambdaUpdateWrapper<ToolApprovalEntity>()
                    .eq(ToolApprovalEntity::getPendingId, pendingId)
                    .set(ToolApprovalEntity::getStatus, status)
                    .set(ToolApprovalEntity::getResolvedAt, LocalDateTime.now());

            if (resolvedBy != null) {
                wrapper.set(ToolApprovalEntity::getResolvedBy, resolvedBy);
            }
            approvalMapper.update(null, wrapper);
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] Failed to update DB status: {}", e.getMessage());
        }
    }

    private String serializeFindings(List<GuardFinding> findings) {
        if (findings == null || findings.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(
                    findings.stream().map(GuardFinding::toMap).toList()
            );
        } catch (JsonProcessingException e) {
            log.warn("[ApprovalWorkflow] Failed to serialize findings: {}", e.getMessage());
            return null;
        }
    }
}
