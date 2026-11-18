package fi.alavesa.idcards.presenting;

import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.api.CardPresentEvent;
import fi.alavesa.idcards.card.CardData;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ChargedProjectiles;
import io.papermc.paper.datacomponent.item.MapId;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * Presenting: F while holding a card in the main hand holds it out; F again, or anything that takes the card out of
 * the hand, puts it away.
 *
 * <p><b>How the pose is made.</b> A server cannot play player animations, but vanilla poses a player by what is in
 * their hand, and a server may tell each client something different about that hand:
 * <ul>
 *   <li>EVERYONE ELSE is told the presenter holds a charged crossbow whose item model is the card. A charged
 *       crossbow is the one item whose idle pose raises both arms, so in third person both arms come up and the
 *       card (a 3D model, placed by its display transform) stands between the hands, facing forward.</li>
 *   <li>THE PRESENTER is told their hand holds an item with a map id. Vanilla draws such an item in first person
 *       as a sheet held in both of the player's own skinned arms; the map picture is the card ({@link CardMaps}).</li>
 * </ul>
 * Both are sent with {@code Player.sendEquipmentChange}: packets only. <b>The real item never changes</b>, so there
 * is nothing to swap back, lose or duplicate, and nothing to restore after a crash: the server's inventory holds the
 * card the whole time, and any inventory resync shows it again. The stand-ins carry a tag ({@code fake}) and are
 * refused and deleted wherever they could turn real (ItemGuard), which only a creative-mode client could try.
 *
 * <p>Presenters and their viewers are refreshed every {@code present.update-interval-ticks}: new players in range
 * get the stand-in, players who left range get the real item back. Main thread only.
 */
public final class PresentService implements Listener {

    private static final class State {
        UUID owner;
        boolean valid;
        int slot, startedTick;
        @Nullable MapView map;
        final Set<UUID> viewers = new HashSet<>();
        boolean offHandHidden;
    }

    private static final Key PRESENTING_MODEL = Key.key("idcards", "id_card_presenting");

    private final IdCardsPlugin plugin;
    private final CardMaps maps;
    private final InspectService inspect;
    private final Map<UUID, State> presenting = new HashMap<>();
    private final Map<UUID, Integer> lastStart = new HashMap<>();
    private final ItemStack othersItem;

    public PresentService(IdCardsPlugin plugin) {
        this.plugin = plugin;
        this.maps = new CardMaps(plugin);
        this.inspect = new InspectService(plugin);
        othersItem = ItemStack.of(Material.CROSSBOW);
        othersItem.setData(DataComponentTypes.ITEM_MODEL, PRESENTING_MODEL);
        othersItem.setData(DataComponentTypes.CHARGED_PROJECTILES, ChargedProjectiles.chargedProjectiles(List.of(ItemStack.of(Material.ARROW))));
        othersItem.editPersistentDataContainer(pdc -> pdc.set(plugin.keys().fake, PersistentDataType.BYTE, (byte) 1));
    }

    /** Starts the timers; call once from onEnable (the intervals are read again on every run). */
    public void start() {
        new org.bukkit.scheduler.BukkitRunnable() {
            int tick;

            @Override
            public void run() {
                tick++;
                if (tick % plugin.settings().presentUpdateInterval == 0) updateViewers();
                if (tick % plugin.settings().inspectInterval == 0) inspect.update(owners());
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public InspectService inspect() {
        return inspect;
    }

    public boolean isPresenting(Player p) {
        return presenting.containsKey(p.getUniqueId());
    }

    /** presenter -> owner of the presented card. */
    public Map<UUID, UUID> owners() {
        Map<UUID, UUID> out = new HashMap<>();
        presenting.forEach((id, s) -> out.put(id, s.owner));
        return out;
    }

    // ------------------------------------------------------------------ start / stop

    public boolean start(Player p) {
        if (isPresenting(p) || plugin.ui().isOpen(p) || p.isDead()) return false;
        ItemStack card = p.getInventory().getItemInMainHand();
        if (!plugin.cards().isCard(card)) return false;
        if (!p.hasPermission("idcard.present")) {
            plugin.lang().send(p, "present.no-permission");
            return false;
        }
        if (!plugin.pack().usable(p)) {
            plugin.lang().send(p, "card.pack-required");
            return false;
        }
        CardData data = plugin.cards().read(card);
        if (data.owner() == null) {
            plugin.lang().send(p, "card.unreadable");
            return false;
        }
        int now = Bukkit.getCurrentTick();
        Integer last = lastStart.get(p.getUniqueId());
        if (last != null && now - last < plugin.settings().presentCooldownTicks) return false; // rate limit, silently
        CardPresentEvent event = new CardPresentEvent(p, data.owner(), data.valid());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        lastStart.put(p.getUniqueId(), now);

        State s = new State();
        s.owner = data.owner();
        s.valid = data.valid();
        s.slot = p.getInventory().getHeldItemSlot();
        s.startedTick = now;
        s.map = maps.acquire(plugin.views().of(s.owner));
        presenting.put(p.getUniqueId(), s);
        showSelf(p, s);
        updateViewers(p, s);
        return true;
    }

    /** What the presenter's own client is shown: the card with a map id, and an empty off hand (both arms are needed). */
    private void showSelf(Player p, State s) {
        if (s.map == null) return; // no map: the holder keeps seeing the plain card item
        p.sendMap(s.map);
        ItemStack self = p.getInventory().getItemInMainHand().clone();
        self.setData(DataComponentTypes.MAP_ID, MapId.mapId(s.map.getId()));
        self.editPersistentDataContainer(pdc -> pdc.set(plugin.keys().fake, PersistentDataType.BYTE, (byte) 1));
        p.sendEquipmentChange(p, EquipmentSlot.HAND, self);
        if (!p.getInventory().getItemInOffHand().isEmpty()) {
            p.sendEquipmentChange(p, EquipmentSlot.OFF_HAND, ItemStack.empty());
            s.offHandHidden = true;
        }
    }

    public void stop(Player p) {
        State s = presenting.remove(p.getUniqueId());
        if (s == null) return;
        ItemStack real = p.getInventory().getItemInMainHand();
        for (UUID id : s.viewers) {
            Player v = Bukkit.getPlayer(id);
            if (v != null && v.isOnline() && p.isOnline()) v.sendEquipmentChange(p, EquipmentSlot.HAND, real);
        }
        if (s.map != null) maps.release(s.map);
        if (p.isOnline()) p.updateInventory(); // the real card and off hand again
        inspect.update(owners());
    }

    public void stopAll() {
        for (UUID id : List.copyOf(presenting.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) stop(p);
        }
        inspect.hideAll();
    }

    // ------------------------------------------------------------------ viewers

    private void updateViewers() {
        int now = Bukkit.getCurrentTick(), max = plugin.settings().presentMaxSeconds * 20;
        for (Map.Entry<UUID, State> e : List.copyOf(presenting.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            State s = e.getValue();
            if (p == null) {
                presenting.remove(e.getKey());
                continue;
            }
            // the card left the hand in a way no event told us about (another plugin, a command)
            if (!plugin.cards().isCard(p.getInventory().getItemInMainHand()) || p.getInventory().getHeldItemSlot() != s.slot
                    || (max > 0 && now - s.startedTick > max)) {
                stop(p);
                continue;
            }
            updateViewers(p, s);
        }
    }

    private void updateViewers(Player p, State s) {
        double r = plugin.settings().presentRenderDistance, r2 = r * r;
        Set<UUID> inRange = new HashSet<>();
        for (Player v : p.getWorld().getNearbyPlayers(p.getLocation(), r)) {
            if (v.equals(p) || !v.canSee(p) || v.getLocation().distanceSquared(p.getLocation()) > r2) continue;
            inRange.add(v.getUniqueId());
            // sent again every round: a client that lost and regained sight of the presenter was told the real item
            v.sendEquipmentChange(p, EquipmentSlot.HAND, othersItem);
        }
        for (UUID id : s.viewers) {
            if (inRange.contains(id)) continue;
            Player v = Bukkit.getPlayer(id);
            if (v != null && v.getWorld().equals(p.getWorld())) v.sendEquipmentChange(p, EquipmentSlot.HAND, p.getInventory().getItemInMainHand());
        }
        s.viewers.clear();
        s.viewers.addAll(inRange);
    }

    /** A name, skin or prefix changed: repaint the maps and HUDs that show this owner. */
    public void refresh(UUID owner) {
        for (Map.Entry<UUID, State> e : presenting.entrySet()) {
            State s = e.getValue();
            Player p = Bukkit.getPlayer(e.getKey());
            if (!s.owner.equals(owner) || s.map == null || p == null) continue;
            maps.paint(s.map, plugin.views().of(owner));
            p.sendMap(s.map);
        }
        inspect.refresh(owner);
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (isPresenting(p)) {
            e.setCancelled(true);
            stop(p);
        } else if (plugin.cards().isCard(p.getInventory().getItemInMainHand())) {
            e.setCancelled(true); // F with a card in the main hand never swaps it away
            start(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        stop(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent e) {
        stop(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p) stop(p);
    }

    /** The player's own inventory screen opens without telling the server; the first click in it does. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && isPresenting(p)) {
            e.setCancelled(true);
            stop(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent e) {
        State s = presenting.get(e.getPlayer().getUniqueId());
        if (s != null && e.getSlot() == s.slot && !plugin.cards().isCard(e.getNewItemStack())) stop(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        stop(e.getEntity());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        stop(e.getPlayer());
        inspect.hide(e.getPlayer());
        lastStart.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        stop(e.getPlayer());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        stop(e.getPlayer());
        inspect.hide(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        stop(e.getPlayer());
    }
}
