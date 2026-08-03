package punishnotify.evidence;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import punishnotify.PendingPunishment;
import punishnotify.PunishNotifyPlugin;
import punishnotify.PunishmentType;
import punishnotify.webhook.DiscordWebhook;

import java.nio.file.Files;
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
    private final HttpUploadServer httpServer;
    private final Logger logger;

    private int timeoutSeconds;
    private String httpHost;
    private int httpPort;
    private String publicUrl;

    public EvidenceManager(PunishNotifyPlugin plugin, DiscordWebhook webhook,
                          HttpUploadServer httpServer) {
        this.plugin = plugin;
        this.webhook = webhook;
        this.httpServer = httpServer;
        this.logger = plugin.getLogger();
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

        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        PendingPunishment punishment = new PendingPunishment(
                type, playerName, playerUuid, reason,
                moderatorName, moderatorUuid, durationSeconds,
                serverName, System.currentTimeMillis(), token
        );

        pending.put(token, punishment);

        notifyModerator(punishment);

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(token) != null) {
                tasks.remove(token);
                webhook.sendAsync(punishment, Collections.emptyList());
                logger.info("Таймаут доказательств для " + playerName
                        + " (" + type.displayName() + ") — вебхук отправлен без файлов.");
            }
        }, timeoutSeconds * 20L);
        tasks.put(token, task);
    }

    private void notifyModerator(PendingPunishment p) {
        Player moderator = p.moderatorUuid() != null
                ? Bukkit.getPlayer(p.moderatorUuid()) : null;
        if (moderator == null) {
            if (p.moderatorUuid() == null) {
                webhook.sendAsync(p, Collections.emptyList());
                logger.info("Наказание от консоли для " + p.playerName()
                        + " — вебхук отправлен без доказательств.");
            } else {
                webhook.sendAsync(p, Collections.emptyList());
                logger.info("Модератор оффлайн для " + p.playerName()
                        + " — вебхук отправлен без доказательств.");
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

        Component message = Component.text()
                .append(Component.text("[PunishNotify] ", NamedTextColor.GOLD))
                .append(Component.text(p.type().emoji() + " " + p.type().displayName() + ": ", NamedTextColor.WHITE))
                .append(Component.text(p.playerName(), NamedTextColor.RED))
                .append(Component.newline())
                .append(Component.text("Прикрепить доказательства?", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text(" [Загрузить] ", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.openUrl(uploadUrl))
                        .hoverEvent(HoverEvent.showText(Component.text("Открыть страницу загрузки"))))
                .append(Component.text(" [Пропустить] ", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/punishnotify skip " + p.token()))
                        .hoverEvent(HoverEvent.showText(Component.text("Отправить без доказательств"))))
                .build();

        moderator.sendMessage(message);
    }

    public void completeWithEvidence(String token, List<Path> files) {
        PendingPunishment p = pending.remove(token);
        if (p == null) {
            logger.warning("Токен " + token + " не найден или уже обработан.");
            return;
        }
        cancelTimeout(token);
        webhook.sendAsync(p, files);
        deleteFiles(files);
        logger.info("Вебхук отправлен для " + p.playerName()
                + " (" + p.type().displayName() + ") с " + files.size() + " файлами.");
    }

    public void skip(String token) {
        PendingPunishment p = pending.remove(token);
        if (p == null) {
            logger.warning("Токен " + token + " не найден или уже обработан.");
            return;
        }
        cancelTimeout(token);
        webhook.sendAsync(p, Collections.emptyList());
        logger.info("Вебхук отправлен для " + p.playerName()
                + " (" + p.type().displayName() + ") без доказательств.");
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

    private void deleteFiles(List<Path> files) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
            }
        }
    }

    public void shutdown() {
        for (BukkitTask task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
        for (PendingPunishment p : pending.values()) {
            webhook.sendAsync(p, Collections.emptyList());
        }
        pending.clear();
    }
}
