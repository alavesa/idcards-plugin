package fi.alavesa.idcards;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The card itself. Paper (NOT a book - books open their own screen and eat
 * the click, one of the old skript's bugs), tagged with a PDC key so 1.21
 * components can't silently lose it, showing name, LuckPerms rank prefix and
 * the "clearance" meta. Right-click presents it: a static two-sided text
 * hologram half a block ahead, gone again after a few seconds or on
 * re-click. Cards can't be dropped and never appear in death drops - a fresh
 * one (with a fresh rank) is issued on every respawn and join.
 */
public final class CardListener implements Listener {

    private static final class Holo {
        final List<TextDisplay> parts = new ArrayList<>();
        long expiresAt;
    }

    private final IdCardsPlugin plugin;
    private final Map<UUID, Holo> holograms = new HashMap<>();

    public CardListener(IdCardsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------- the card

    private boolean isCard(ItemStack item) {
        return item != null && item.getType() == Material.PAPER && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer()
                .has(plugin.key("card"), PersistentDataType.BYTE);
    }

    /** LuckPerms rank prefix, colored; a plain fallback when LP is absent. */
    private Component rank(Player player) {
        try {
            User user = LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                String prefix = user.getCachedData().getMetaData().getPrefix();
                if (prefix != null && !prefix.isBlank()) {
                    return LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(prefix.replace('§', '&'));
                }
                return Component.text(user.getPrimaryGroup(), NamedTextColor.WHITE);
            }
        } catch (IllegalStateException | NoClassDefFoundError ignored) {
            // LuckPerms not installed
        }
        return Component.text("Personnel", NamedTextColor.WHITE);
    }

    private String clearance(Player player) {
        try {
            User user = LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                String value = user.getCachedData().getMetaData().getMetaValue("clearance");
                if (value != null) return value;
            }
        } catch (IllegalStateException | NoClassDefFoundError ignored) { }
        return "0";
    }

    public ItemStack buildCard(Player player) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("Identification Card", NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Name: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(player.getName(), NamedTextColor.WHITE)),
            Component.text("Rank: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(rank(player)),
            Component.text("Clearance: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("Level " + clearance(player), NamedTextColor.WHITE))));
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("lab_idcard"));
        meta.setCustomModelDataComponent(cmd);
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(plugin.key("card"), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Issue a card; replaces any stale ones so ranks never go out of date. */
    public void issue(Player player, boolean force) {
        boolean had = false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isCard(item)) {
                if (force) item.setAmount(0);
                else had = true;
            }
        }
        if (force || !had) {
            player.getInventory().addItem(buildCard(player)).values().forEach(left ->
                player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
    }

    // ------------------------------------------------------------- lifecycle

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        issue(event.getPlayer(), true); // fresh rank on every login
        if (plugin.getConfig().getBoolean("hide-nametags", true)) {
            Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(IdCardsPlugin.TEAM_NAME);
            if (team != null) team.addEntry(event.getPlayer().getName());
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> issue(event.getPlayer(), true));
    }

    /** The card is part of you - it can't be dropped or die with you. */
    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isCard(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text(
                "Foundation property stays on your person.", NamedTextColor.GRAY, TextDecoration.ITALIC));
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isCard);
        removeHologram(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeHologram(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------- hologram

    @EventHandler
    public void onPresent(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isCard(event.getItem())) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (holograms.containsKey(player.getUniqueId())) {
            removeHologram(player.getUniqueId());
            return;
        }
        Holo holo = new Holo();
        holo.expiresAt = System.currentTimeMillis()
            + plugin.getConfig().getInt("hologram-seconds", 3) * 1000L;
        Location base = player.getLocation().clone()
            .add(player.getLocation().getDirection().setY(0).normalize().multiply(0.6))
            .add(0, 1.0, 0);
        Component text = Component.text()
            .append(Component.text("SCP ", NamedTextColor.DARK_GRAY, TextDecoration.BOLD))
            .append(Component.text(player.getName(), NamedTextColor.GRAY))
            .append(Component.newline())
            .append(rank(player))
            .append(Component.newline())
            .append(Component.text("L" + clearance(player), NamedTextColor.WHITE, TextDecoration.BOLD))
            .build();
        // static and two-sided: one display per face, back rotated 180
        for (float turn : new float[]{0f, 180f}) {
            Location at = base.clone();
            at.setYaw(player.getLocation().getYaw() + turn);
            at.setPitch(0);
            holo.parts.add(player.getWorld().spawn(at, TextDisplay.class, display -> {
                display.text(text);
                display.setBillboard(Display.Billboard.FIXED);
                display.setShadowed(true);
                display.setAlignment(TextDisplay.TextAlignment.LEFT);
                display.setBackgroundColor(Color.fromARGB(150, 200, 50, 50));
                display.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 1),
                    new Vector3f(0.5f, 0.5f, 0.5f), new AxisAngle4f(0, 0, 0, 1)));
                display.addScoreboardTag(IdCardsPlugin.TAG_HOLO);
                display.setPersistent(false);
            }));
        }
        holograms.put(player.getUniqueId(), holo);
        player.getWorld().playSound(base, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.4f);
    }

    /** Called every 10 ticks: expired presentations fold away. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(holograms.keySet())) {
            if (holograms.get(id).expiresAt <= now) removeHologram(id);
        }
    }

    private void removeHologram(UUID player) {
        Holo holo = holograms.remove(player);
        if (holo != null) holo.parts.forEach(TextDisplay::remove);
    }

    public void removeAllHolograms() {
        for (UUID id : new ArrayList<>(holograms.keySet())) removeHologram(id);
    }
}
