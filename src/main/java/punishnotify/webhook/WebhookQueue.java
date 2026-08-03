package punishnotify.webhook;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import punishnotify.PendingPunishment;
import punishnotify.PunishNotifyPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class WebhookQueue {

    private record QueuedEntry(PendingPunishment punishment, List<Path> files, int attempts, String lastError) {}

    private final PunishNotifyPlugin plugin;
    private final DiscordWebhook webhook;
    private final Logger logger;

    private final Map<String, QueuedEntry> queue = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private boolean enabled = true;
    private int maxAttempts = 5;
    private long retryIntervalTicks = 30 * 20L;
    private BukkitTask timerTask;

    public WebhookQueue(PunishNotifyPlugin plugin, DiscordWebhook webhook, Logger logger) {
        this.plugin = plugin;
        this.webhook = webhook;
        this.logger = logger;
    }

    public void configure(boolean enabled, int maxAttempts, int retryIntervalSeconds) {
        this.enabled = enabled;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryIntervalTicks = Math.max(20L, retryIntervalSeconds * 20L);
    }

    public void start() {
        timerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::process,
                retryIntervalTicks, retryIntervalTicks);
    }

    public void submit(PendingPunishment p, List<Path> files, String context) {
        if (!webhook.enabled()) {
            deleteFiles(files);
            return;
        }
        if (!enabled) {
            webhook.sendAsync(p, files).whenComplete((ok, ex) -> {
                deleteFiles(files);
                if (!Boolean.TRUE.equals(ok)) {
                    logger.warning("Вебхук для " + p.playerName()
                            + " не отправлен (ретраи отключены в конфиге).");
                }
            });
            return;
        }
        String token = p.token();
        if (inFlight.add(token)) {
            send(p, files, context, 0, token);
        } else {
            queue.put(token, new QueuedEntry(p, files, 0, "ожидание очереди"));
        }
    }

    private void send(PendingPunishment p, List<Path> files, String context, int attempts, String token) {
        webhook.sendAsync(p, files).whenComplete((ok, ex) -> {
            inFlight.remove(token);
            if (Boolean.TRUE.equals(ok)) {
                queue.remove(token);
                deleteFiles(files);
                logger.info("Вебхук доставлен для " + p.playerName() + " (" + context + ")"
                        + (attempts > 0 ? ", попытка " + (attempts + 1) : ""));
            } else {
                String reason = ex != null
                        ? ex.getClass().getSimpleName() + ": " + ex.getMessage()
                        : "отклонён Discord";
                int nextAttempts = attempts + 1;
                if (nextAttempts >= maxAttempts) {
                    queue.remove(token);
                    deleteFiles(files);
                    logger.severe("Вебхук для " + p.playerName() + " не отправлен после "
                            + maxAttempts + " попыток (" + reason + ") — отчёт отброшен.");
                } else {
                    queue.put(token, new QueuedEntry(p, files, nextAttempts, reason));
                    logger.warning("Вебхук для " + p.playerName() + " в очереди на повтор: " + reason
                            + " (попытка " + nextAttempts + "/" + maxAttempts + ").");
                }
            }
        });
    }

    private void process() {
        if (queue.isEmpty()) {
            return;
        }
        for (QueuedEntry e : List.copyOf(queue.values())) {
            String token = e.punishment().token();
            if (inFlight.add(token)) {
                send(e.punishment(), e.files(), "повторная попытка", e.attempts(), token);
            }
        }
    }

    public int queuedCount() {
        return queue.size();
    }

    public void shutdown() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
        if (!queue.isEmpty()) {
            logger.warning("При остановке сервера в очереди осталось " + queue.size()
                    + " неотправленных вебхуков — они будут потеряны.");
            for (QueuedEntry e : queue.values()) {
                deleteFiles(e.files());
            }
            queue.clear();
        }
        inFlight.clear();
    }

    private void deleteFiles(List<Path> files) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
            }
        }
    }
}
