package fi.alavesa.idcards;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Team;

import java.util.List;
import java.util.stream.Stream;

/**
 * Physical identity for the facility: player nametags are hidden server-wide
 * (a scoreboard team with nametag visibility NEVER), and identity lives on a
 * card in your pocket instead - issued on join and respawn, showing the
 * holder's LuckPerms rank, presented as a static two-sided hologram on
 * right-click. Replaces the old id-cards.sk, whose NBT tagging silently
 * stopped working on 1.21 components.
 */
public final class IdCardsPlugin extends JavaPlugin {

    public static final String TEAM_NAME = "idc_hidden";
    public static final String TAG_HOLO = "idcards.holo";

    private CardListener cards;

    @Override
    public void onEnable() {
        getConfig().addDefault("hologram-seconds", 3);
        getConfig().addDefault("hide-nametags", true);
        getConfig().options().copyDefaults(true);
        saveConfig();
        cards = new CardListener(this);
        getServer().getPluginManager().registerEvents(cards, this);
        getServer().getScheduler().runTaskTimer(this, cards::tick, 40L, 10L);
        if (getConfig().getBoolean("hide-nametags", true)) {
            applyNametags(true);
        }
        // leftover holograms from a crash or reload
        getServer().getScheduler().runTask(this, () ->
            Bukkit.getWorlds().forEach(world ->
                world.getEntitiesByClass(TextDisplay.class).stream()
                    .filter(d -> d.getScoreboardTags().contains(TAG_HOLO))
                    .forEach(TextDisplay::remove)));
        getLogger().info("IdCards enabled - LuckPerms "
            + (Bukkit.getPluginManager().getPlugin("LuckPerms") != null ? "found" : "NOT found (ranks show as Personnel)"));
    }

    @Override
    public void onDisable() {
        if (cards != null) cards.removeAllHolograms();
    }

    public void applyNametags(boolean hide) {
        var board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(TEAM_NAME);
        if (hide) {
            if (team == null) team = board.registerNewTeam(TEAM_NAME);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            for (Player online : Bukkit.getOnlinePlayers()) {
                team.addEntry(online.getName());
            }
        } else if (team != null) {
            team.unregister();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("idcards.admin")) return error(sender, "No permission.");
        if (args.length == 0) return usage(sender);
        switch (args[0].toLowerCase()) {
            case "give" -> {
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1])
                    : (sender instanceof Player p ? p : null);
                if (target == null) return error(sender, "Player not found.");
                cards.issue(target, true);
                sender.sendMessage(Component.text("Fresh ID card issued to " + target.getName() + ".",
                    NamedTextColor.AQUA));
                return true;
            }
            case "nametags" -> {
                if (args.length < 2) return usage(sender);
                boolean hide = args[1].equalsIgnoreCase("off");
                // "/idcard nametags off" hides them; "on" shows them again
                applyNametags(hide);
                getConfig().set("hide-nametags", hide);
                saveConfig();
                sender.sendMessage(Component.text("Nametags are now "
                    + (hide ? "hidden - identity lives on the card." : "visible."), NamedTextColor.AQUA));
                return true;
            }
            default -> { return usage(sender); }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return switch (args.length) {
            case 1 -> Stream.of("give", "nametags")
                .filter(o -> o.startsWith(args[0].toLowerCase())).toList();
            case 2 -> args[0].equalsIgnoreCase("nametags")
                ? Stream.of("on", "off").filter(o -> o.startsWith(args[1].toLowerCase())).toList()
                : List.of();
            default -> List.of();
        };
    }

    public NamespacedKey key(String name) { return new NamespacedKey(this, name); }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text("/idcard give [player] | nametags on|off", NamedTextColor.AQUA));
        return true;
    }

    private boolean error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return true;
    }
}
