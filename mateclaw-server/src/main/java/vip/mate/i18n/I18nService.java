package vip.mate.i18n;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import vip.mate.system.service.SystemSettingService;

import java.util.Locale;

/**
 * 国际化服务 — 统一的消息解析入口
 * <p>
 * 根据 {@link SystemSettingService#getLanguage()} 全局语言设置解析消息。
 * 缓存 Locale 对象（语言切换频率极低），避免每次调用都解析字符串。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class I18nService {

    private final MessageSource messageSource;
    private final SystemSettingService settingService;

    /** 缓存的 Locale 和对应的语言字符串 */
    private volatile Locale cachedLocale;
    private volatile String cachedLang;

    /**
     * 解析国际化消息
     *
     * @param key  消息键（如 "tool.read_file.desc"）
     * @param args 占位符参数
     * @return 解析后的消息文本，找不到 key 时返回 key 本身
     */
    public String msg(String key, Object... args) {
        Locale locale = resolveLocale();
        try {
            return messageSource.getMessage(key, args, locale);
        } catch (Exception e) {
            log.debug("[I18n] Missing key: {} (locale={})", key, locale);
            return key;
        }
    }

    /**
     * 获取当前语言的 locale 标识（用于 PromptLoader 等需要 locale 字符串的场景）
     *
     * @return "zh" 或 "en"
     */
    public String currentLocaleTag() {
        String lang = settingService.getLanguage();
        return lang.startsWith("en") ? "en" : "zh";
    }

    /**
     * 清除缓存的 Locale（语言切换时调用）
     */
    public void clearLocaleCache() {
        cachedLocale = null;
        cachedLang = null;
        log.info("[I18n] Locale cache cleared");
    }

    private Locale resolveLocale() {
        String lang = settingService.getLanguage();
        if (lang.equals(cachedLang) && cachedLocale != null) {
            return cachedLocale;
        }
        Locale locale = lang.startsWith("en") ? Locale.US : Locale.CHINA;
        cachedLang = lang;
        cachedLocale = locale;
        return locale;
    }
}
