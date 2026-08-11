package punishnotify.webhook;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import punishnotify.LocaleManager;
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
    private LocaleManager lm;

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

    public void setLocaleManager(LocaleManager lm) {
        this.lm = lm;
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
                    logger.warning("Webhook for " + p.playerName() + " not sent (retries disabled in config).");
                }
            });
            return;
        }
        String token = p.token();
        if (inFlight.add(token)) {
            send(p, files, context, 0, token);
        } else {
            queue.put(token, new QueuedEntry(p, files, 0, "waiting in queue"));
        }
    }

    private void send(PendingPunishment p, List<Path> files, String context, int attempts, String token) {
        webhook.sendAsync(p, files).whenComplete((ok, ex) -> {
            inFlight.remove(token);
            if (Boolean.TRUE.equals(ok)) {
                queue.remove(token);
                deleteFiles(files);
                logger.info("Webhook delivered for " + p.playerName() + " (" + context + ")"
                        + (attempts > 0 ? ", attempt " + (attempts + 1) : ""));
            } else {
                String reason = ex != null
                        ? ex.getClass().getSimpleName() + ": " + ex.getMessage()
                        : "rejected by Discord";
                int nextAttempts = attempts + 1;
                if (nextAttempts >= maxAttempts) {
                    queue.remove(token);
                    deleteFiles(files);
                    logger.severe("Webhook for " + p.playerName() + " failed after "
                            + maxAttempts + " attempts (" + reason + ") — report discarded.");
                } else {
                    queue.put(token, new QueuedEntry(p, files, nextAttempts, reason));
                    logger.warning("Webhook for " + p.playerName() + " queued for retry: " + reason
                            + " (attempt " + nextAttempts + "/" + maxAttempts + ").");
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
                send(e.punishment(), e.files(), "retry", e.attempts(), token);
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
            logger.warning("Server shutting down with " + queue.size()
                    + " unsent webhooks in queue — they will be lost.");
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
