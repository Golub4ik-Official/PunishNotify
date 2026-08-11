package punishnotify;

public enum PunishmentType {

    BAN(0xED4245, "🔨"),
    UNBAN(0x57F287, "✅"),
    MUTE(0xFEE75C, "🔇"),
    UNMUTE(0x57F287, "🔊"),
    KICK(0xFFA500, "👢"),
    WARN(0xE8A838, "⚠"),
    JAIL(0x8B4513, "🔒"),
    UNJAIL(0x57F287, "🔓");

    private final int color;
    private final String emoji;

    PunishmentType(int color, String emoji) {
        this.color = color;
        this.emoji = emoji;
    }

    public int color() {
        return color;
    }

    /** Returns the localized display name from the locale manager. */
    public String displayName(LocaleManager lm) {
        return lm.get("punishment." + name().toLowerCase());
    }

    /**
     * Returns the raw enum name as a fallback (used in non-localized contexts,
     * e.g. internal logging before locale is loaded).
     */
    public String displayName() {
        return name();
    }

    public String emoji() {
        return emoji;
    }

    public String configKey() {
        return name().toLowerCase();
    }
}
