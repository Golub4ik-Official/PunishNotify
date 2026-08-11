package punishnotify;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import punishnotify.command.PunishNotifyCommand;
import punishnotify.evidence.EvidenceManager;
import punishnotify.evidence.HttpUploadServer;
import punishnotify.listener.CommandListener;
import punishnotify.listener.EssentialsListener;
import punishnotify.webhook.DiscordWebhook;
import punishnotify.webhook.WebhookQueue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class PunishNotifyPlugin extends JavaPlugin {

    private DiscordWebhook webhook;
    private WebhookQueue queue;
    private EvidenceManager evidenceManager;
    private HttpUploadServer httpServer;
    private LocaleManager localeManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        initializeLocale();
        initializeComponents();
        registerListeners();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("punishnotify",
                        "Manage PunishNotify",
                        new PunishNotifyCommand(this, evidenceManager)));

        getLogger().info(localeManager.get("log.enabled", getDescription().getVersion()));
    }

    @Override
    public void onDisable() {
        if (evidenceManager != null) {
            evidenceManager.shutdown();
        }
        if (httpServer != null) {
            httpServer.stop();
        }
        getLogger().info(localeManager != null ? localeManager.get("log.disabled") : "PunishNotify disabled.");
    }

    public void reinitializeComponents() {
        reloadConfig();

        // Reload locale first — so all components get the updated locale
        String lang = getConfig().getString("language", "en");
        localeManager.load(lang);

        if (webhook != null) {
            webhook.reload(
                    getConfig().getString("discord.webhook-url", ""),
                    getConfig().getString("discord.username", "PunishNotify"),
                    getConfig().getString("discord.avatar-url", "")
            );
            webhook.setLocaleManager(localeManager);
        }
        if (queue != null) {
            queue.configure(
                    getConfig().getBoolean("discord.retry-enabled", true),
                    getConfig().getInt("discord.retry-max-attempts", 5),
                    getConfig().getInt("discord.retry-interval-seconds", 30)
            );
            queue.setLocaleManager(localeManager);
        }
        if (evidenceManager != null) {
            evidenceManager.setLocaleManager(localeManager);
            evidenceManager.reloadConfig();
        }
        if (httpServer != null) {
            httpServer.stop();
            httpServer.setLocaleManager(localeManager);
            httpServer.loadConfig(
                    getConfig().getInt("http-server.port", 8734),
                    getConfig().getString("http-server.bind", "0.0.0.0"),
                    getConfig().getLong("evidence.max-file-size-mb", 25),
                    getConfig().getInt("evidence.max-files", 10)
            );
            loadUploadPage();
            if (getConfig().getBoolean("http-server.enabled", true)) {
                httpServer.start(
                        getConfig().getInt("http-server.port", 8734),
                        getConfig().getString("http-server.bind", "0.0.0.0")
                );
            }
        }
    }

    /** Returns the active locale manager. */
    public LocaleManager getLocaleManager() {
        return localeManager;
    }

    private void initializeLocale() {
        localeManager = new LocaleManager(this);
        String lang = getConfig().getString("language", "en");
        localeManager.load(lang);
    }

    private void initializeComponents() {
        String webhookUrl = getConfig().getString("discord.webhook-url", "");
        String username = getConfig().getString("discord.username", "PunishNotify");
        String avatarUrl = getConfig().getString("discord.avatar-url", "");

        webhook = new DiscordWebhook(webhookUrl, username, avatarUrl, getLogger());
        webhook.setLocaleManager(localeManager);

        queue = new WebhookQueue(this, webhook, getLogger());
        queue.setLocaleManager(localeManager);
        queue.configure(
                getConfig().getBoolean("discord.retry-enabled", true),
                getConfig().getInt("discord.retry-max-attempts", 5),
                getConfig().getInt("discord.retry-interval-seconds", 30));
        queue.start();

        int httpPort = getConfig().getInt("http-server.port", 8734);
        String httpBind = getConfig().getString("http-server.bind", "0.0.0.0");
        long maxFileSizeMb = getConfig().getLong("evidence.max-file-size-mb", 25);
        int maxFiles = getConfig().getInt("evidence.max-files", 10);

        evidenceManager = new EvidenceManager(this, webhook, queue, null);
        evidenceManager.setLocaleManager(localeManager);
        evidenceManager.reloadConfig();

        httpServer = new HttpUploadServer(getLogger(), evidenceManager);
        httpServer.setLocaleManager(localeManager);
        httpServer.loadConfig(httpPort, httpBind, maxFileSizeMb, maxFiles);

        loadUploadPage();

        if (getConfig().getBoolean("http-server.enabled", true)) {
            httpServer.start(httpPort, httpBind);
        }
    }

    private void registerListeners() {
        CommandListener commandListener = new CommandListener(evidenceManager);
        commandListener.setLocaleManager(localeManager);
        getServer().getPluginManager().registerEvents(commandListener, this);
        getLogger().info(localeManager.get("log.listeners_registered"));

        if (Bukkit.getPluginManager().getPlugin("Essentials") == null) {
            getLogger().warning(localeManager.get("log.essentials_not_found"));
            return;
        }
        try {
            EssentialsListener essentialsListener = new EssentialsListener(evidenceManager);
            essentialsListener.setLocaleManager(localeManager);
            getServer().getPluginManager().registerEvents(essentialsListener, this);
            getLogger().info(localeManager.get("log.essentials_registered"));
        } catch (NoClassDefFoundError e) {
            getLogger().warning(localeManager.get("log.essentials_api_error", e.getMessage()));
        }
    }

    private void loadUploadPage() {
        try (InputStream is = getResource("web/upload.html")) {
            if (is != null) {
                String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                int maxFiles = getConfig().getInt("evidence.max-files", 10);
                long maxMb = getConfig().getLong("evidence.max-file-size-mb", 25);
                html = localeManager.applyToHtml(html, maxFiles, maxMb);
                html = html.replace("{{TOKEN}}", "{{TOKEN}}"); // preserve token placeholder
                httpServer.setUploadPageHtml(html);
            } else {
                getLogger().warning(localeManager.get("log.upload_page_not_found"));
            }
        } catch (IOException e) {
            getLogger().warning(localeManager.get("log.upload_page_error", e.getMessage()));
        }
    }
}
