package punishnotify.listener;

import net.ess3.api.events.JailStatusChangeEvent;
import net.ess3.api.events.MuteStatusChangeEvent;
import net.essentialsx.api.v2.events.UserKickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import punishnotify.PunishmentType;
import punishnotify.evidence.EvidenceManager;

import java.util.Optional;
import java.util.UUID;

public class EssentialsListener implements Listener {

    private final EvidenceManager evidenceManager;

    public EssentialsListener(EvidenceManager evidenceManager) {
        this.evidenceManager = evidenceManager;
    }

    @EventHandler
    public void onMute(MuteStatusChangeEvent event) {
        net.ess3.api.IUser affected = event.getAffected();
        net.ess3.api.IUser controller = event.getController();

        String moderatorName = "Консоль";
        UUID moderatorUuid = null;
        Player base = controller.getBase();
        if (base instanceof Player player) {
            moderatorName = player.getName();
            moderatorUuid = player.getUniqueId();
        }

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
                event.getReason() != null ? event.getReason() : "Не указана",
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

        String moderatorName = "Консоль";
        UUID moderatorUuid = null;
        Player base = controller.getBase();
        if (base instanceof Player player) {
            moderatorName = player.getName();
            moderatorUuid = player.getUniqueId();
        }

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

        String moderatorName = "Консоль";
        UUID moderatorUuid = null;
        if (kicker != null) {
            Player kickerBase = kicker.getBase();
            if (kickerBase instanceof Player player) {
                moderatorName = player.getName();
                moderatorUuid = player.getUniqueId();
            }
        }

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
