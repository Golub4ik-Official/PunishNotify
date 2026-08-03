package punishnotify;

public enum PunishmentType {

    BAN(0xED4245, "Бан", "🔨"),
    UNBAN(0x57F287, "Разбан", "✅"),
    MUTE(0xFEE75C, "Мут", "🔇"),
    UNMUTE(0x57F287, "Размут", "🔊"),
    KICK(0xFFA500, "Кик", "👢"),
    WARN(0xE8A838, "Предупреждение", "⚠"),
    JAIL(0x8B4513, "Тюрьма", "🔒"),
    UNJAIL(0x57F287, "Освобождение", "🔓");

    private final int color;
    private final String displayName;
    private final String emoji;

    PunishmentType(int color, String displayName, String emoji) {
        this.color = color;
        this.displayName = displayName;
        this.emoji = emoji;
    }

    public int color() {
        return color;
    }

    public String displayName() {
        return displayName;
    }

    public String emoji() {
        return emoji;
    }

    public String configKey() {
        return name().toLowerCase();
    }
}
