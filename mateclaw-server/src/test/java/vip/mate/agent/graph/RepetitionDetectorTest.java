package vip.mate.agent.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RepetitionDetector 单元测试
 */
class RepetitionDetectorTest {

    private RepetitionDetector detector;

    @BeforeEach
    void setUp() {
        detector = new RepetitionDetector();
    }

    @Test
    @DisplayName("正常文本不触发重复检测")
    void shouldNotTriggerForNormalText() {
        assertFalse(detector.appendAndCheck("Hello, world! This is a normal response. "));
        assertFalse(detector.appendAndCheck("It contains various sentences and ideas. "));
        assertFalse(detector.appendAndCheck("No repetition should be detected here. "));
        assertFalse(detector.appendAndCheck("The detector only flags degenerate patterns. "));
        assertFalse(detector.isRepetitionDetected());
    }

    @Test
    @DisplayName("短文本不触发检测（低于最小内容长度）")
    void shouldNotTriggerForShortText() {
        assertFalse(detector.appendAndCheck("短"));
        assertFalse(detector.appendAndCheck("短"));
        assertFalse(detector.appendAndCheck("短"));
        assertFalse(detector.isRepetitionDetected());
    }

    @Test
    @DisplayName("连续重复相同片段触发检测")
    void shouldTriggerForRepeatedPattern() {
        // 构造足够长的前缀以超过最小检测长度
        StringBuilder sb = new StringBuilder();
        sb.append("这是一段正常的开头文本。".repeat(5));
        detector.appendAndCheck(sb.toString());

        // 现在重复同一模式多次
        String pattern = "不吃香菜，喝冰美式。";
        boolean triggered = false;
        for (int i = 0; i < 20; i++) {
            if (detector.appendAndCheck(pattern)) {
                triggered = true;
                break;
            }
        }
        assertTrue(triggered, "Should detect repetition after many identical appends");
        assertTrue(detector.isRepetitionDetected());
    }

    @Test
    @DisplayName("检测到重复后持续返回 true")
    void shouldKeepReturningTrueAfterDetection() {
        // 直接构造重复内容
        String pattern = "重复片段测试内容。";
        StringBuilder bulk = new StringBuilder();
        bulk.append("正常的前缀内容，长度足够。".repeat(5));
        for (int i = 0; i < 20; i++) {
            bulk.append(pattern);
        }
        detector.appendAndCheck(bulk.toString());

        // 后续调用应该继续返回 true
        assertTrue(detector.appendAndCheck("任何新内容"));
        assertTrue(detector.isRepetitionDetected());
    }

    @Test
    @DisplayName("reset 后重新检测")
    void shouldResetState() {
        // 先触发检测
        String pattern = "重复片段测试。";
        StringBuilder bulk = new StringBuilder("前缀".repeat(50));
        for (int i = 0; i < 20; i++) {
            bulk.append(pattern);
        }
        detector.appendAndCheck(bulk.toString());

        // reset
        detector.reset();
        assertFalse(detector.isRepetitionDetected());
        assertFalse(detector.appendAndCheck("正常的新内容"));
    }

    @Test
    @DisplayName("null 和空字符串不触发也不异常")
    void shouldHandleNullAndEmpty() {
        assertFalse(detector.appendAndCheck(null));
        assertFalse(detector.appendAndCheck(""));
        assertFalse(detector.isRepetitionDetected());
    }

    @Test
    @DisplayName("Unicode 中文重复模式正确检测")
    void shouldDetectChineseRepetition() {
        StringBuilder sb = new StringBuilder("初始化内容填充。".repeat(10));
        String pattern = "已记住。以后涉及点餐时我会提醒你：";
        for (int i = 0; i < 20; i++) {
            sb.append(pattern);
        }
        boolean triggered = detector.appendAndCheck(sb.toString());
        assertTrue(triggered, "Should detect Chinese character repetition");
    }
}
