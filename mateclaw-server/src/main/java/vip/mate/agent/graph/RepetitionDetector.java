package vip.mate.agent.graph;

import lombok.extern.slf4j.Slf4j;

/**
 * 流式输出重复检测器
 * <p>
 * 检测 LLM 流式输出中的退化重复模式（degenerate repetition），
 * 当检测到内容在滑动窗口内高度重复时返回 true，调用方应截断 LLM 流。
 * <p>
 * 算法：维护一个滑动窗口缓冲区，每次追加新 delta 后，
 * 检查窗口尾部是否存在连续重复的 n-gram 模式。
 *
 * @author MateClaw Team
 */
@Slf4j
public class RepetitionDetector {

    /** 滑动窗口大小（字符数） */
    private static final int WINDOW_SIZE = 1024;

    /** 最小重复片段长度 */
    private static final int MIN_PATTERN_LEN = 8;

    /** 最大检测的模式长度 */
    private static final int MAX_PATTERN_LEN = 200;

    /** 模式需要连续出现的最小次数才判定为重复 */
    private static final int MIN_REPEATS = 4;

    /** 已累积内容的最小长度才开始检测（避免误判短内容） */
    private static final int MIN_CONTENT_LEN = 200;

    private final StringBuilder buffer = new StringBuilder();
    private boolean repetitionDetected = false;

    /**
     * 追加新的 delta 并检测是否存在重复
     *
     * @param delta 新增的文本片段
     * @return true 表示检测到退化重复，调用方应截断流
     */
    public boolean appendAndCheck(String delta) {
        if (delta == null || delta.isEmpty() || repetitionDetected) {
            return repetitionDetected;
        }

        buffer.append(delta);

        // 内容太短，不检测
        if (buffer.length() < MIN_CONTENT_LEN) {
            return false;
        }

        // 保持窗口大小
        if (buffer.length() > WINDOW_SIZE * 2) {
            buffer.delete(0, buffer.length() - WINDOW_SIZE);
        }

        // 在窗口尾部检测重复模式
        String window = buffer.toString();
        int windowLen = window.length();

        // 从短模式到长模式扫描
        for (int patternLen = MIN_PATTERN_LEN;
             patternLen <= Math.min(MAX_PATTERN_LEN, windowLen / MIN_REPEATS);
             patternLen++) {

            // 取窗口末尾的 pattern
            String pattern = window.substring(windowLen - patternLen);

            // 向前数这个 pattern 连续出现了几次
            int count = 1;
            int pos = windowLen - patternLen * 2;
            while (pos >= 0) {
                String segment = window.substring(pos, pos + patternLen);
                if (segment.equals(pattern)) {
                    count++;
                    pos -= patternLen;
                } else {
                    break;
                }
            }

            if (count >= MIN_REPEATS) {
                repetitionDetected = true;
                log.warn("[RepetitionDetector] Detected degenerate repetition: " +
                                "pattern length={}, repeats={}, pattern preview=\"{}\"",
                        patternLen, count,
                        pattern.length() > 50 ? pattern.substring(0, 50) + "..." : pattern);
                return true;
            }
        }

        return false;
    }

    /**
     * 重置检测器状态
     */
    public void reset() {
        buffer.setLength(0);
        repetitionDetected = false;
    }

    /**
     * 是否已检测到重复
     */
    public boolean isRepetitionDetected() {
        return repetitionDetected;
    }
}
