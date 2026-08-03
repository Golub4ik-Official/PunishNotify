package punishnotify.listener;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import punishnotify.PunishmentType;
import punishnotify.evidence.EvidenceManager;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommandListener implements Listener {

    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)(mo|y|d|h|m|w|s)");

    private final EvidenceManager evidenceManager;

    public CommandListener(EvidenceManager evidenceManager) {
        this.evidenceManager = evidenceManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        handle(event.getPlayer(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerCommand(ServerCommandEvent event) {
        handle(event.getSender(), event.getCommand());
    }

    private void handle(CommandSender sender, String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        String[] parts = message.trim().split("\\s+");
        String rawCmd = parts[0].replaceFirst("^/+", "");
        if (rawCmd.contains(":")) {
            rawCmd = rawCmd.substring(rawCmd.indexOf(':') + 1);
        }
        String cmd = rawCmd.toLowerCase();
        if (parts.length < 2) {
            return;
        }

        switch (cmd) {
            case "ban", "tempban", "ban-ip", "banip", "tempban-ip", "tempbanip" ->
                    handleBan(sender, parts, cmd);
            case "pardon", "unban", "pardon-ip", "pardonip", "unban-ip", "unbanip" ->
                    handleUnban(sender, parts);
            case "warn" -> handleWarn(sender, parts);
            default -> {
            }
        }
    }

    private void handleBan(CommandSender sender, String[] parts, String cmd) {
        if (!can(sender, "essentials.ban", "essentials.tempban", "minecraft.command.ban")) {
            return;
        }

        String target = parts[1];
        String reason = "";
        long durationSeconds = -1;

        if (cmd.startsWith("tempban")) {
            if (parts.length < 3) {
                return;
            }
            durationSeconds = parseDuration(parts[2]);
            reason = join(parts, 3);
        } else {
            reason = join(parts, 2);
        }

        fire(sender, PunishmentType.BAN, target, reason, durationSeconds);
    }

    private void handleUnban(CommandSender sender, String[] parts) {
        if (!can(sender, "essentials.pardon", "minecraft.command.pardon")) {
            return;
        }
        fire(sender, PunishmentType.UNBAN, parts[1], "", -1);
    }

    private void handleWarn(CommandSender sender, String[] parts) {
        if (!can(sender, "essentials.warn")) {
            return;
        }
        fire(sender, PunishmentType.WARN, parts[1], join(parts, 2), -1);
    }

    private void fire(CommandSender sender, PunishmentType type, String target,
                      String reason, long durationSeconds) {
        String moderatorName = "Консоль";
        UUID moderatorUuid = null;
        if (sender instanceof Player player) {
            moderatorName = player.getName();
            moderatorUuid = player.getUniqueId();
        }

        evidenceManager.onPunishment(
                type,
                target,
                resolvePlayerUuid(target),
                reason != null && !reason.isBlank() ? reason : "Не указана",
                moderatorName,
                moderatorUuid,
                durationSeconds,
                Bukkit.getServer().getName()
        );
    }

    private static UUID resolvePlayerUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline != null ? offline.getUniqueId() : null;
    }

    private static boolean can(CommandSender sender, String... permissions) {
        if (sender.isOp() || sender.hasPermission("punishnotify.admin")) {
            return true;
        }
        for (String perm : permissions) {
            if (sender.hasPermission(perm)) {
                return true;
            }
        }
        return false;
    }

    private static String join(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    static long parseDuration(String time) {
        if (time == null || time.isBlank()) {
            return -1;
        }
        String s = time.trim().toLowerCase();
        if (s.equals("forever") || s.equals("perm") || s.equals("permanent")) {
            return -1;
        }

        long totalSeconds = 0;
        Matcher matcher = DURATION_PATTERN.matcher(s);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            long value = Long.parseLong(matcher.group(1));
            totalSeconds += value * switch (matcher.group(2)) {
                case "mo" -> 2592000L;
                case "y" -> 31536000L;
                case "w" -> 604800L;
                case "d" -> 86400L;
                case "h" -> 3600L;
                case "m" -> 60L;
                default -> 1L;
            };
        }
        return found ? totalSeconds : -1;
    }
}
