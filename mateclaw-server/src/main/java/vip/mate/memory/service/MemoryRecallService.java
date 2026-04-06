package vip.mate.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.model.MemoryRecallEntity;
import vip.mate.memory.repository.MemoryRecallMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆召回追踪与评分服务
 * <p>
 * 记录 workspace 文件的召回频率、查询多样性等信号，
 * 计算加权评分用于 Dreaming 记忆整合。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRecallService {

    private final MemoryRecallMapper recallMapper;
    private final MemoryProperties properties;
    private final ObjectMapper objectMapper;

    private static final int MAX_QUERY_HASHES = 32;

    /**
     * 记录一次文件召回
     */
    public void recordRecall(Long agentId, String filename, String snippetText, String userQueryHash) {
        if (agentId == null || filename == null || filename.isBlank()) {
            return;
        }

        String snippetHash = sha256(snippetText);
        String preview = snippetText != null && snippetText.length() > 200
                ? snippetText.substring(0, 200)
                : snippetText;

        MemoryRecallEntity existing = recallMapper.selectOne(
                new LambdaQueryWrapper<MemoryRecallEntity>()
                        .eq(MemoryRecallEntity::getAgentId, agentId)
                        .eq(MemoryRecallEntity::getFilename, filename)
                        .eq(MemoryRecallEntity::getDeleted, 0)
                        .last("LIMIT 1"));

        LocalDateTime now = LocalDateTime.now();

        if (existing != null) {
            existing.setRecallCount(existing.getRecallCount() + 1);
            existing.setDailyCount(existing.getDailyCount() + 1);
            existing.setLastRecalledAt(now);
            existing.setSnippetHash(snippetHash);
            existing.setSnippetPreview(preview);

            // 追加 query hash（去重，最多 MAX_QUERY_HASHES 个）
            if (userQueryHash != null) {
                List<String> hashes = parseQueryHashes(existing.getQueryHashes());
                if (!hashes.contains(userQueryHash) && hashes.size() < MAX_QUERY_HASHES) {
                    hashes.add(userQueryHash);
                }
                existing.setQueryHashes(toJson(hashes));
            }

            recallMapper.updateById(existing);
        } else {
            MemoryRecallEntity entity = new MemoryRecallEntity();
            entity.setAgentId(agentId);
            entity.setFilename(filename);
            entity.setSnippetHash(snippetHash);
            entity.setSnippetPreview(preview);
            entity.setRecallCount(1);
            entity.setDailyCount(1);
            entity.setLastRecalledAt(now);
            entity.setPromoted(false);
            entity.setScore(0.0);
            entity.setCreateTime(now);
            entity.setUpdateTime(now);
            entity.setDeleted(0);

            if (userQueryHash != null) {
                entity.setQueryHashes(toJson(List.of(userQueryHash)));
            }

            recallMapper.insert(entity);
        }
    }

    /**
     * 重置所有记录的 dailyCount（在每轮 dreaming 开始时调用）
     */
    public void resetDailyCounts(Long agentId) {
        recallMapper.update(null,
                new LambdaUpdateWrapper<MemoryRecallEntity>()
                        .eq(MemoryRecallEntity::getAgentId, agentId)
                        .eq(MemoryRecallEntity::getDeleted, 0)
                        .set(MemoryRecallEntity::getDailyCount, 0));
    }

    /**
     * 获取未提升的候选列表
     */
    public List<MemoryRecallEntity> listCandidates(Long agentId) {
        return recallMapper.selectList(
                new LambdaQueryWrapper<MemoryRecallEntity>()
                        .eq(MemoryRecallEntity::getAgentId, agentId)
                        .eq(MemoryRecallEntity::getPromoted, false)
                        .eq(MemoryRecallEntity::getDeleted, 0)
                        .orderByDesc(MemoryRecallEntity::getScore));
    }

    /**
     * 计算加权评分，返回超过阈值的高分候选
     */
    public List<MemoryRecallEntity> computeScores(Long agentId) {
        List<MemoryRecallEntity> candidates = listCandidates(agentId);
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 归一化参数
        int maxRecallCount = candidates.stream()
                .mapToInt(MemoryRecallEntity::getRecallCount)
                .max().orElse(1);
        int maxQueryDiversity = candidates.stream()
                .mapToInt(e -> parseQueryHashes(e.getQueryHashes()).size())
                .max().orElse(1);

        LocalDateTime now = LocalDateTime.now();
        double halfLifeDays = 7.0;
        double threshold = properties.getEmergenceScoreThreshold();

        for (MemoryRecallEntity entry : candidates) {
            // 1. 频率 (0.30)
            double frequency = (double) entry.getRecallCount() / Math.max(maxRecallCount, 1);

            // 2. 时效性 (0.25) — 指数衰减
            double recency = 0.0;
            if (entry.getLastRecalledAt() != null) {
                long daysSinceRecall = ChronoUnit.DAYS.between(entry.getLastRecalledAt(), now);
                recency = Math.exp(-0.693 * daysSinceRecall / halfLifeDays); // ln(2) ≈ 0.693
            }

            // 3. 查询多样性 (0.20)
            int queryCount = parseQueryHashes(entry.getQueryHashes()).size();
            double diversity = (double) queryCount / Math.max(maxQueryDiversity, 1);

            // 4. 内容新鲜度 (0.15) — 根据文件名日期
            double freshness = computeFreshness(entry.getFilename(), now);

            // 5. 召回速度 (0.10) — dailyCount / recallCount
            double velocity = entry.getRecallCount() > 0
                    ? (double) entry.getDailyCount() / entry.getRecallCount()
                    : 0.0;

            double score = 0.30 * frequency
                    + 0.25 * recency
                    + 0.20 * diversity
                    + 0.15 * freshness
                    + 0.10 * velocity;

            entry.setScore(score);
            recallMapper.updateById(entry);
        }

        return candidates.stream()
                .filter(e -> e.getScore() >= threshold)
                .sorted(Comparator.comparingDouble(MemoryRecallEntity::getScore).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 标记候选为已提升
     */
    public void markPromoted(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        recallMapper.update(null,
                new LambdaUpdateWrapper<MemoryRecallEntity>()
                        .in(MemoryRecallEntity::getId, ids)
                        .set(MemoryRecallEntity::getPromoted, true));
    }

    // ==================== 内部工具方法 ====================

    private double computeFreshness(String filename, LocalDateTime now) {
        // 从 "memory/2026-04-01.md" 或 "memory/2026-04-01.md#section" 提取日期
        if (filename == null || !filename.startsWith("memory/")) {
            return 0.5; // 非 daily note 给中间值
        }
        try {
            String datePart = filename.replace("memory/", "");
            // 剥离 #anchor（片段级追踪产生的 section key）
            int hashIdx = datePart.indexOf('#');
            if (hashIdx > 0) {
                datePart = datePart.substring(0, hashIdx);
            }
            datePart = datePart.replace(".md", "");
            String[] parts = datePart.split("-");
            if (parts.length == 3) {
                LocalDateTime fileDate = LocalDateTime.of(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        0, 0);
                long daysAgo = ChronoUnit.DAYS.between(fileDate, now);
                return Math.max(0, 1.0 - (double) daysAgo / 30.0); // 30 天线性衰减
            }
        } catch (Exception ignored) {
        }
        return 0.5;
    }

    private List<String> parseQueryHashes(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String sha256(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
