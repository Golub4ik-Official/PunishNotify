package punishnotify;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Manages plugin localization.
 * <p>
 * Load priority:
 * 1. External file: plugins/PunishNotify/lang/<code>.yml  (user-editable)
 * 2. Built-in jar resource: lang/<code>.yml
 * 3. Built-in fallback: lang/en.yml
 * </p>
 */
public class LocaleManager {

    private YamlConfiguration locale;
    private YamlConfiguration fallback;
    private final JavaPlugin plugin;
    private final Logger logger;

    public LocaleManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Loads the locale for the given language code.
     * Falls back to English if the specified locale is not found.
     *
     * @param langCode language code, e.g. "en", "ru"
     */
    public void load(String langCode) {
        // Load fallback (en) first so every key has a value
        fallback = loadBuiltin("en");

        if ("en".equalsIgnoreCase(langCode)) {
            locale = fallback;
            logger.info(format(get("log.locale_loaded"), "en"));
            return;
        }

        // Try external file first
        File external = new File(plugin.getDataFolder(), "lang/" + langCode + ".yml");
        if (external.exists()) {
            locale = YamlConfiguration.loadConfiguration(external);
            logger.info(format(get("log.locale_loaded"), langCode));
            return;
        }

        // Try built-in jar resource
        YamlConfiguration builtin = loadBuiltin(langCode);
        if (builtin != null) {
            locale = builtin;
            logger.info(format(get("log.locale_loaded"), langCode));
            return;
        }

        // Not found — use English
        logger.warning(format(get("log.locale_not_found"), langCode));
        locale = fallback;
    }

    /**
     * Returns a localized string for the given dot-separated key.
     * Falls back to the English value, then to the key itself if not found.
     */
    public String get(String key) {
        if (locale != null) {
            String value = locale.getString(key);
            if (value != null) return value;
        }
        if (fallback != null) {
            String value = fallback.getString(key);
            if (value != null) return value;
        }
        return key;
    }

    /**
     * Returns a localized string with {@code String.format()} placeholders filled in.
     * All placeholders in locale files must use {@code %s}.
     */
    public String get(String key, Object... args) {
        String raw = get(key);
        if (args == null || args.length == 0) return raw;
        try {
            return String.format(raw, args);
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * Replaces all {@code {{i18n.KEY}}} placeholders in the given HTML string
     * with the corresponding locale values. Also injects JS locale object for
     * dynamic strings (file size errors, etc.).
     *
     * @param html     source HTML template
     * @param maxFiles maximum number of files (from config)
     * @param maxMb    maximum file size in MB (from config)
     * @return processed HTML with all placeholders replaced
     */
    public String applyToHtml(String html, int maxFiles, long maxMb) {
        if (html == null) return "";

        // Replace static i18n placeholders
        html = replaceKey(html, "web.title",               get("web.title"));
        html = replaceKey(html, "web.subtitle",            get("web.subtitle"));
        html = replaceKey(html, "web.drop_hint",           get("web.drop_hint"));
        html = replaceKey(html, "web.drop_limit",          get("web.drop_limit", maxFiles, maxMb));
        html = replaceKey(html, "web.skip_btn",            get("web.skip_btn"));
        html = replaceKey(html, "web.upload_btn",          get("web.upload_btn"));
        html = replaceKey(html, "web.uploading",           get("web.uploading"));
        html = replaceKey(html, "web.processing",          get("web.processing"));
        html = replaceKey(html, "web.success_title",       get("web.success_title"));
        html = replaceKey(html, "web.skip_loading",        get("web.skip_loading"));
        html = replaceKey(html, "web.skip_success_title",  get("web.skip_success_title"));
        html = replaceKey(html, "web.skip_success_subtitle", get("web.skip_success_subtitle"));
        html = replaceKey(html, "web.remove_title",        get("web.remove_title"));

        // Inject JS locale constants for dynamic client-side strings
        String jsLocale = buildJsLocale(maxFiles, maxMb);
        html = html.replace("/* {{I18N_JS}} */", jsLocale);

        return html;
    }

    // ------------------------------------------------------------------ //

    private static String replaceKey(String html, String key, String value) {
        return html.replace("{{i18n." + key + "}}", escapeHtml(value));
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String buildJsLocale(int maxFiles, long maxMb) {
        return "const I18N = {" +
                "success_title:" + jsStr(get("web.success_title")) + "," +
                "success_subtitle_tpl:" + jsStr(get("web.success_subtitle")) + "," +
                "error_prefix:" + jsStr(get("web.error_prefix")) + "," +
                "error_network:" + jsStr(get("web.error_network")) + "," +
                "error_format:" + jsStr(get("web.error_format")) + "," +
                "error_too_large:" + jsStr(get("web.error_too_large", maxMb)) + "," +
                "error_limit:" + jsStr(get("web.error_limit", maxFiles)) + "," +
                "uploading:" + jsStr(get("web.uploading")) + "," +
                "processing:" + jsStr(get("web.processing")) + "," +
                "skip_btn:" + jsStr(get("web.skip_btn")) + "," +
                "upload_btn:" + jsStr(get("web.upload_btn")) + "," +
                "skip_loading:" + jsStr(get("web.skip_loading")) + "," +
                "skip_success_title:" + jsStr(get("web.skip_success_title")) + "," +
                "skip_success_subtitle:" + jsStr(get("web.skip_success_subtitle")) + "," +
                "remove_title:" + jsStr(get("web.remove_title")) +
                "};";
    }

    private static String jsStr(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

    private static String format(String template, Object... args) {
        try {
            return String.format(template, args);
        } catch (Exception e) {
            return template;
        }
    }

    private YamlConfiguration loadBuiltin(String langCode) {
        String path = "lang/" + langCode + ".yml";
        try (InputStream is = plugin.getResource(path)) {
            if (is == null) return null;
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.warning("Failed to load built-in locale '" + langCode + "': " + e.getMessage());
            return null;
        }
    }
}
