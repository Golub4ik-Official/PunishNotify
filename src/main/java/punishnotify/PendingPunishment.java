package punishnotify;

import java.util.UUID;

public record PendingPunishment(
    PunishmentType type,
    String playerName,
    UUID playerUuid,
    String reason,
    String moderatorName,
    UUID moderatorUuid,
    long durationSeconds,
    String serverName,
    long createdAt,
    String token
) {
    public boolean isPermanent() {
        return durationSeconds <= 0;
    }

    public String durationText() {
        if (isPermanent()) {
            return "Навсегда";
        }
        long hours = durationSeconds / 3600;
        long minutes = (durationSeconds % 3600) / 60;
        if (hours > 0 && minutes > 0) {
            return hours + " ч. " + minutes + " мин.";
        } else if (hours > 0) {
            return hours + " ч.";
        }
        return minutes + " мин.";
    }
}
