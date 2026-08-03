package punishnotify.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import punishnotify.PunishNotifyPlugin;
import punishnotify.evidence.EvidenceManager;

public class PunishNotifyCommand implements BasicCommand {

    private final PunishNotifyPlugin plugin;
    private final EvidenceManager evidenceManager;

    public PunishNotifyCommand(PunishNotifyPlugin plugin, EvidenceManager evidenceManager) {
        this.plugin = plugin;
        this.evidenceManager = evidenceManager;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        CommandSender sender = stack.getSender();

        if (args.length == 0) {
            sender.sendMessage(Component.text()
                    .append(Component.text("PunishNotify ", NamedTextColor.GOLD))
                    .append(Component.text("v" + plugin.getDescription().getVersion(), NamedTextColor.GRAY))
                    .build());
            sender.sendMessage(Component.text("/punishnotify reload", NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.runCommand("/punishnotify reload")));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> doReload(sender);
            case "skip" -> doSkip(sender, args);
            default -> sender.sendMessage(Component.text("Неизвестная подкоманда. Используйте /punishnotify reload", NamedTextColor.RED));
        }
    }

    private void doReload(CommandSender sender) {
        if (!sender.hasPermission("punishnotify.reload")) {
            sender.sendMessage(Component.text("У вас нет прав на эту команду.", NamedTextColor.RED));
            return;
        }

        plugin.reloadConfig();
        plugin.reinitializeComponents();

        sender.sendMessage(Component.text("[PunishNotify] Конфигурация перезагружена.", NamedTextColor.GREEN));
    }

    private void doSkip(CommandSender sender, String[] args) {
        if (!sender.hasPermission("punishnotify.skip")) {
            sender.sendMessage(Component.text("У вас нет прав на эту команду.", NamedTextColor.RED));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Использование: /punishnotify skip <token>", NamedTextColor.RED));
            return;
        }

        String token = args[1];
        if (!evidenceManager.hasToken(token)) {
            sender.sendMessage(Component.text("[PunishNotify] Токен не найден или уже обработан.", NamedTextColor.RED));
            return;
        }

        evidenceManager.skip(token);
        sender.sendMessage(Component.text("[PunishNotify] Вебхук отправлен без доказательств.", NamedTextColor.GREEN));
    }

    @Override
    public @NotNull String permission() {
        return "punishnotify.admin";
    }
}
