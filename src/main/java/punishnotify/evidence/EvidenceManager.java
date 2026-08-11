package punishnotify.evidence;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import punishnotify.LocaleManager;
import punishnotify.PendingPunishment;
import punishnotify.PunishNotifyPlugin;
import punishnotify.PunishmentType;
import punishnotify.webhook.DiscordWebhook;
import punishnotify.webhook.WebhookQueue;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class EvidenceManager {

    private final ConcurrentHashMap<String, PendingPunishment> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BukkitTask> tasks = new ConcurrentHashMap<>();

    private final PunishNotifyPlugin plugin;
    private final DiscordWebhook webhook;
    private final WebhookQueue queue;
    private final HttpUploadServer httpServer;
    private final Logger logger;

    private int timeoutSeconds;
    private String httpHost;
    private int httpPort;
    private String publicUrl;

    private LocaleManager lm;

    public EvidenceManager(PunishNotifyPlugin plugin, DiscordWebhook webhook,
                          WebhookQueue queue, HttpUploadServer httpServer) {
        this.plugin = plugin;
        this.webhook = webhook;
        this.queue = queue;
        this.httpServer = httpServer;
        this.logger = plugin.getLogger();
    }

    public void setLocaleManager(LocaleManager lm) {
        this.lm = lm;
    }

    public void reloadConfig() {
        timeoutSeconds = plugin.getConfig().getInt("evidence.timeout-seconds", 120);
        httpPort = plugin.getConfig().getInt("http-server.port", 8734);
        String bind = plugin.getConfig().getString("http-server.bind", "0.0.0.0");
        httpHost = "localhost".equals(bind) ? "127.0.0.1" : bind;
        publicUrl = plugin.getConfig().getString("http-server.public-url", "");
        if (publicUrl != null && publicUrl.endsWith("/")) {
            publicUrl = publicUrl.substring(0, publicUrl.length() - 1);
        }
    }

    public boolean isEventEnabled(PunishmentType type) {
        return plugin.getConfig().getBoolean("events." + type.configKey(), true);
    }

    public void onPunishment(PunishmentType type, String playerName, UUID playerUuid,
                              String reason, String moderatorName, UUID moderatorUuid,
                              long durationSeconds, String serverName) {
        if (!isEventEnabled(type)) {
            return;
        }
        if (!webhook.enabled()) {
            return;
        }

        String normalizedReason = (reason != null && !reason.isBlank()) ? reason : t("embed.no_reason");
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        PendingPunishment punishment = new PendingPunishment(
                type, playerName, playerUuid, normalizedReason,
                moderatorName, moderatorUuid, durationSeconds,
                serverName, System.currentTimeMillis(), token
        );

        pending.put(token, punishment);

        notifyModerator(punishment);

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(token) != null) {
                tasks.remove(token);
                queue.submit(punishment, Collections.emptyList(), "timeout");
                logger.info(t("log.timeout_queued", playerName, type.displayName(lm)));
            }
        }, timeoutSeconds * 20L);
        tasks.put(token, task);
    }

    private void notifyModerator(PendingPunishment p) {
        Player moderator = p.moderatorUuid() != null
                ? Bukkit.getPlayer(p.moderatorUuid()) : null;
        if (moderator == null) {
            if (p.moderatorUuid() == null) {
                queue.submit(p, Collections.emptyList(), "console");
                logger.info(t("log.console_punishment", p.playerName()));
            } else {
                queue.submit(p, Collections.emptyList(), "moderator offline");
                logger.info(t("log.moderator_offline", p.playerName()));
            }
            pending.remove(p.token());
            BukkitTask task = tasks.remove(p.token());
            if (task != null) task.cancel();
            return;
        }

        String baseUrl = (publicUrl != null && !publicUrl.isEmpty())
                ? publicUrl
                : "http://" + httpHost + ":" + httpPort;
        String uploadUrl = baseUrl + "/?token=" + p.token();

        String typeLine = p.type().emoji() + " " + p.type().displayName(lm) + ": ";

        Component message = Component.text()
                .append(Component.text(t("notify.header"), NamedTextColor.GOLD))
                .append(Component.text(typeLine, NamedTextColor.WHITE))
                .append(Component.text(p.playerName(), NamedTextColor.RED))
                .append(Component.newline())
                .append(Component.text(t("notify.attach_question"), NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text(t("notify.upload_button"), NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.openUrl(uploadUrl))
                        .hoverEvent(HoverEvent.showText(Component.text(t("notify.upload_hover")))))
                .append(Component.text(t("notify.skip_button"), NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/punishnotify skip " + p.token()))
                        .hoverEvent(HoverEvent.showText(Component.text(t("notify.skip_hover")))))
                .build();

        moderator.sendMessage(message);
    }

    public void completeWithEvidence(String token, List<Path> files) {
        PendingPunishment p = pending.remove(token);
        if (p == null) {
            logger.warning(t("log.token_not_found", token));
            return;
        }
        cancelTimeout(token);
        queue.submit(p, files, "evidence");
        logger.info(t("log.evidence_queued", p.playerName(), p.type().displayName(lm), files.size()));
    }

    public void skip(String token) {
        PendingPunishment p = pending.remove(token);
        if (p == null) {
            logger.warning(t("log.token_not_found", token));
            return;
        }
        cancelTimeout(token);
        queue.submit(p, Collections.emptyList(), "skip");
        logger.info(t("log.skip_queued", p.playerName(), p.type().displayName(lm)));
    }

    public boolean hasToken(String token) {
        return pending.containsKey(token);
    }

    private void cancelTimeout(String token) {
        BukkitTask task = tasks.remove(token);
        if (task != null) {
            task.cancel();
        }
    }

    public void shutdown() {
        for (BukkitTask task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
        for (PendingPunishment p : pending.values()) {
            queue.submit(p, Collections.emptyList(), "server shutdown");
        }
        pending.clear();
        queue.shutdown();
    }

    /** Helper: get locale string, graceful null-lm fallback. */
    private String t(String key, Object... args) {
        if (lm == null) return key;
        return lm.get(key, args);
    }
}
