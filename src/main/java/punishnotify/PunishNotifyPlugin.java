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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class PunishNotifyPlugin extends JavaPlugin {

    private DiscordWebhook webhook;
    private WebhookQueue queue;
    private EvidenceManager evidenceManager;
    private HttpUploadServer httpServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        initializeComponents();
        registerListeners();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("punishnotify",
                        "Управление PunishNotify",
                        new PunishNotifyCommand(this, evidenceManager)));

        getLogger().info("PunishNotify v" + getDescription().getVersion() + " включён.");
    }

    @Override
    public void onDisable() {
        if (evidenceManager != null) {
            evidenceManager.shutdown();
        }
        if (httpServer != null) {
            httpServer.stop();
        }
        getLogger().info("PunishNotify выключен.");
    }

    public void reinitializeComponents() {
        reloadConfig();
        if (webhook != null) {
            webhook.reload(
                    getConfig().getString("discord.webhook-url", ""),
                    getConfig().getString("discord.username", "PunishNotify"),
                    getConfig().getString("discord.avatar-url", "")
            );
        }
        if (queue != null) {
            queue.configure(
                    getConfig().getBoolean("discord.retry-enabled", true),
                    getConfig().getInt("discord.retry-max-attempts", 5),
                    getConfig().getInt("discord.retry-interval-seconds", 30)
            );
        }
        if (evidenceManager != null) {
            evidenceManager.reloadConfig();
        }
        if (httpServer != null) {
            httpServer.stop();
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

    private void initializeComponents() {
        String webhookUrl = getConfig().getString("discord.webhook-url", "");
        String username = getConfig().getString("discord.username", "PunishNotify");
        String avatarUrl = getConfig().getString("discord.avatar-url", "");

        webhook = new DiscordWebhook(webhookUrl, username, avatarUrl, getLogger());

        queue = new WebhookQueue(this, webhook, getLogger());
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
        evidenceManager.reloadConfig();

        httpServer = new HttpUploadServer(getLogger(), evidenceManager);
        httpServer.loadConfig(httpPort, httpBind, maxFileSizeMb, maxFiles);

        loadUploadPage();

        if (getConfig().getBoolean("http-server.enabled", true)) {
            httpServer.start(httpPort, httpBind);
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new CommandListener(evidenceManager), this);
        getLogger().info("Слушатель команд наказаний зарегистрирован.");

        if (Bukkit.getPluginManager().getPlugin("Essentials") == null) {
            getLogger().warning("EssentialsX не найден. События наказаний не будут отслеживаться.");
            return;
        }
        try {
            getServer().getPluginManager().registerEvents(
                    new EssentialsListener(evidenceManager), this);
            getLogger().info("Слушатель событий EssentialsX зарегистрирован.");
        } catch (NoClassDefFoundError e) {
            getLogger().warning("EssentialsX API не совместим: " + e.getMessage());
        }
    }

    private void loadUploadPage() {
        try (InputStream is = getResource("web/upload.html")) {
            if (is != null) {
                String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                httpServer.setUploadPageHtml(html);
            } else {
                getLogger().warning("Страница загрузки (web/upload.html) не найдена в ресурсах.");
            }
        } catch (IOException e) {
            getLogger().warning("Ошибка загрузки страницы: " + e.getMessage());
        }
    }
}
