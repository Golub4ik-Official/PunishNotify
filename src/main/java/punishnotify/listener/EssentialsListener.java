package punishnotify.listener;

import net.ess3.api.events.JailStatusChangeEvent;
import net.ess3.api.events.MuteStatusChangeEvent;
import net.essentialsx.api.v2.events.UserKickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import punishnotify.LocaleManager;
import punishnotify.PunishmentType;
import punishnotify.evidence.EvidenceManager;

import java.util.Optional;
import java.util.UUID;

public class EssentialsListener implements Listener {

    private final EvidenceManager evidenceManager;
    private LocaleManager lm;

    public EssentialsListener(EvidenceManager evidenceManager) {
        this.evidenceManager = evidenceManager;
    }

    public void setLocaleManager(LocaleManager lm) {
        this.lm = lm;
    }

    @EventHandler
    public void onMute(MuteStatusChangeEvent event) {
        net.ess3.api.IUser affected = event.getAffected();
        net.ess3.api.IUser controller = event.getController();

        String consoleName = lm != null ? lm.get("embed.console") : "Console";
        String noReason = lm != null ? lm.get("embed.no_reason") : "Not specified";

        String moderatorName = controller != null && controller.getBase() instanceof Player p ? p.getName() : consoleName;
        UUID moderatorUuid = controller != null && controller.getBase() instanceof Player p ? p.getUniqueId() : null;

        PunishmentType type = event.getValue() ? PunishmentType.MUTE : PunishmentType.UNMUTE;
        long durationSeconds = -1;

        Optional<Long> timestamp = event.getTimestamp();
        if (timestamp.isPresent() && event.getValue()) {
            long remaining = (timestamp.get() - System.currentTimeMillis()) / 1000;
            if (remaining > 0) {
                durationSeconds = remaining;
            }
        }

        evidenceManager.onPunishment(
                type,
                affected.getName(),
                affected.getUUID(),
                event.getReason() != null ? event.getReason() : noReason,
                moderatorName,
                moderatorUuid,
                durationSeconds,
                Bukkit.getServer().getName()
        );
    }

    @EventHandler
    public void onJail(JailStatusChangeEvent event) {
        net.ess3.api.IUser affected = event.getAffected();
        net.ess3.api.IUser controller = event.getController();

        String consoleName = lm != null ? lm.get("embed.console") : "Console";

        String moderatorName = controller != null && controller.getBase() instanceof Player p ? p.getName() : consoleName;
        UUID moderatorUuid = controller != null && controller.getBase() instanceof Player p ? p.getUniqueId() : null;

        PunishmentType type = event.getValue() ? PunishmentType.JAIL : PunishmentType.UNJAIL;

        evidenceManager.onPunishment(
                type,
                affected.getName(),
                affected.getUUID(),
                "",
                moderatorName,
                moderatorUuid,
                -1,
                Bukkit.getServer().getName()
        );
    }

    @EventHandler
    public void onKick(UserKickEvent event) {
        com.earth2me.essentials.IUser kicked = event.getKicked();
        com.earth2me.essentials.IUser kicker = event.getKicker();

        if (kicked.getBase().isBanned()) {
            return;
        }

        String playerName = kicked.getName();
        UUID playerUuid = kicked.getUUID();

        String moderatorName = kicker != null && kicker.getBase() instanceof Player p ? p.getName() : (lm != null ? lm.get("embed.console") : "Console");
        UUID moderatorUuid = kicker != null && kicker.getBase() instanceof Player p ? p.getUniqueId() : null;

        evidenceManager.onPunishment(
                PunishmentType.KICK,
                playerName,
                playerUuid,
                event.getReason(),
                moderatorName,
                moderatorUuid,
                -1,
                Bukkit.getServer().getName()
        );
    }
}
