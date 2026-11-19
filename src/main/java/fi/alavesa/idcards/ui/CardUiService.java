package fi.alavesa.idcards.ui;

import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.card.CardData;
import fi.alavesa.idcards.nms.CameraBridge;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * The full-screen card: opening, closing, and holding the player still while it is open.
 *
 * <p><b>HUD channel: a boss bar.</b> The card is the name of a boss bar that only this player has. Of the channels
 * a server can write to, it is the only one no other plugin can overwrite: titles and the action bar are single
 * slots that the last sender wins, chat scrolls. Boss bars stack, and the text shader ignores where vanilla put the
 * name, so other plugins' bars above or below do not move the card. (The pack blanks the blue bar graphic.)
 *
 * <p><b>Freezing: the camera packet.</b> While the card is open the client's camera is put on an invisible
 * item_display that RIDES the player (see {@link CameraBridge} for why this needs one packet outside the API). The
 * client then ignores movement keys and sends no movement at all, and the mouse turns nothing visible: no jitter,
 * because nothing is corrected after the fact. Clicks, the F key, number keys and the mouse wheel still reach the
 * server, which is how the left click closes the card and how everything else is refused here.
 *
 * <p>The riding matters. A click is aimed from the camera entity, and the camera sits inside the player's own hit
 * box: a free-standing camera makes the client attack ITSELF on a left click, which vanilla servers answer with a
 * kick ("invalid entity attacked"). The client never targets an entity that shares a vehicle with the camera, and
 * a passenger shares its vehicle's: so the camera is a passenger of the player. One exception remains in the
 * client: an entity whose box CONTAINS the ray's start is always a target. So the camera must also be outside the
 * player's box, not merely on its top face: a 0.02 block high spacer (an Interaction entity) rides the player and
 * the camera rides the spacer. The camera ends up 0.2 blocks above the eyes.
 *
 * <p>A player who opens the card in mid-air is also seated on a second invisible entity, because vanilla kicks
 * clients that hover without sending movement. When the packet cannot be sent (other server software, changed
 * internals) the fallback is that seat plus setting the rotation back every tick, which holds but shakes when the
 * mouse moves.
 *
 * <p>Nothing about the freeze is stored in the player's saved data except one marker ({@code ui_open}); the anchor
 * is not saved either. After a crash the client simply has its own camera again on the next login, and the marker
 * tells {@link #onJoin} to tidy up.
 */
public final class CardUiService implements Listener {

    public enum CloseReason { CLICK, COMMAND, DAMAGE, DEATH, QUIT, TELEPORT, WORLD, GAMEMODE, PLUGIN, CARD_GONE }

    private static final class Session {
        UUID owner;
        BossBar bar;
        @Nullable ItemDisplay anchor, seat;
        @Nullable Interaction spacer;
        Location origin;
        boolean cameraLocked, seated, teleporting;
        int openedTick;
    }

    private static final float SPACER_HEIGHT = 0.02f;

    private final IdCardsPlugin plugin;
    private final CameraBridge camera;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Integer> lastClosed = new HashMap<>();

    public CardUiService(IdCardsPlugin plugin, CameraBridge camera) {
        this.plugin = plugin;
        this.camera = camera;
        // the rotation lock of the fallback method, and a watchdog for anchors that vanished
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public boolean isOpen(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    private boolean useCamera() {
        return camera.available() && !plugin.settings().freezeMethod.equals("mount");
    }

    // ------------------------------------------------------------------ open / close

    /** Opens the card of {@code owner} for {@code p}. Returns false when it did not open. */
    public boolean open(Player p, UUID owner) {
        if (isOpen(p) || p.isDead() || !p.isOnline()) return false;
        Integer closed = lastClosed.get(p.getUniqueId());
        if (closed != null && Bukkit.getCurrentTick() - closed < plugin.settings().uiOpenCooldownTicks) return false;
        plugin.presenting().stop(p);
        plugin.presenting().inspect().hide(p);

        Session s = new Session();
        s.owner = owner;
        s.origin = p.getLocation();
        s.openedTick = Bukkit.getCurrentTick();
        boolean airborne = !((Entity) p).isOnGround() && !p.isFlying();
        if (!useCamera() || airborne) {
            // a passenger cannot walk, and vanilla does not kick a hovering passenger
            p.leaveVehicle();
            s.seat = helper(p, p.getLocation());
            s.seated = s.seat.addPassenger(p);
        }
        if (useCamera()) {
            // player <- spacer <- camera; see the class comment: this is what makes a left click safe
            s.spacer = p.getWorld().spawn(p.getEyeLocation(), Interaction.class, e -> {
                e.setPersistent(false);
                e.setVisibleByDefault(false);
                e.setInteractionWidth(0.02f);
                e.setInteractionHeight(SPACER_HEIGHT);
                e.getPersistentDataContainer().set(plugin.keys().helper, PersistentDataType.BYTE, (byte) 1);
            });
            p.showEntity(plugin, s.spacer);
            s.anchor = helper(p, p.getEyeLocation());
            p.addPassenger(s.spacer);
            s.spacer.addPassenger(s.anchor);
        }
        s.bar = BossBar.bossBar(render(owner, p), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        p.showBossBar(s.bar);
        p.getPersistentDataContainer().set(plugin.keys().uiOpen, PersistentDataType.BYTE, (byte) 1);
        sessions.put(p.getUniqueId(), s);
        if (s.anchor != null) {
            // the client must have received the anchor before it can look through it: entities reach clients at
            // the end of the tick they were spawned in
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (sessions.get(p.getUniqueId()) == s && s.anchor.isValid()) s.cameraLocked = camera.setCamera(p, s.anchor);
            }, 2L);
        }
        if (plugin.settings().uiSounds) p.playSound(p.getLocation(), "idcards:card_open", 1f, 1f);
        return true;
    }

    public void close(Player p, CloseReason reason) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        lastClosed.put(p.getUniqueId(), Bukkit.getCurrentTick());
        p.hideBossBar(s.bar);
        p.getPersistentDataContainer().remove(plugin.keys().uiOpen);
        if (s.cameraLocked) camera.setCamera(p, p);
        if (s.anchor != null) s.anchor.remove();
        if (s.spacer != null) {
            p.removePassenger(s.spacer);
            s.spacer.remove();
        }
        if (s.seat != null) {
            s.seat.removePassenger(p);
            s.seat.remove();
        }
        boolean online = reason != CloseReason.QUIT && p.isOnline();
        // The client kept simulating its own (hidden) player while the camera was away: put it back where the
        // server has it, looking where it looked. Not when the close itself is a teleport, a death or a world change.
        if (online && (s.cameraLocked || s.seated) && (reason == CloseReason.CLICK || reason == CloseReason.COMMAND
                || reason == CloseReason.DAMAGE || reason == CloseReason.GAMEMODE || reason == CloseReason.PLUGIN || reason == CloseReason.CARD_GONE)) {
            s.teleporting = true;
            sessions.put(p.getUniqueId(), s); // so our own teleport is not refused by onTeleport's caller checks
            try {
                p.teleport(s.origin, PlayerTeleportEvent.TeleportCause.PLUGIN);
            } finally {
                sessions.remove(p.getUniqueId());
            }
        }
        if (online) {
            p.updateInventory();
            if (plugin.settings().uiSounds && reason != CloseReason.DEATH) p.playSound(p.getLocation(), "idcards:card_close", 1f, 1f);
        }
    }

    public void closeAll(CloseReason reason) {
        for (UUID id : List.copyOf(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) close(p, reason);
        }
    }

    private Component render(UUID owner, Player viewer) {
        return plugin.renderer().render(plugin.views().of(owner), false, plain(viewer));
    }

    /** True for players who chose the one-line text card (/idcard plain). */
    public boolean plain(Player p) {
        return p.getPersistentDataContainer().has(plugin.keys().plain);
    }

    /** Switches between the drawn card and the text card; returns the new state. */
    public boolean togglePlain(Player p) {
        boolean now = !plain(p);
        if (now) p.getPersistentDataContainer().set(plugin.keys().plain, PersistentDataType.BYTE, (byte) 1);
        else p.getPersistentDataContainer().remove(plugin.keys().plain);
        return now;
    }

    /** A name, skin or prefix changed: redraw the open cards that show this owner. */
    public void refresh(UUID owner) {
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && e.getValue().owner.equals(owner)) e.getValue().bar.name(render(owner, p));
        }
    }

    public void refreshAll() {
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) e.getValue().bar.name(render(e.getValue().owner, p));
        }
    }

    private void tick() {
        if (sessions.isEmpty()) return;
        for (Map.Entry<UUID, Session> e : List.copyOf(sessions.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            Session s = e.getValue();
            if (p == null) continue;
            // killed by a command: without them nothing holds the player
            if ((s.anchor != null && (!s.anchor.isValid() || !s.spacer.isValid())) || (s.seat != null && !s.seat.isValid())) {
                close(p, CloseReason.PLUGIN);
                continue;
            }
            if (s.spacer != null) { // something shook the stack apart: a camera off the player is the unsafe state
                if (!p.getPassengers().contains(s.spacer)) p.addPassenger(s.spacer);
                if (!s.spacer.getPassengers().contains(s.anchor)) s.spacer.addPassenger(s.anchor);
            }
            if (!s.cameraLocked && s.seated) {
                Location l = p.getLocation();
                if (l.getYaw() != s.origin.getYaw() || l.getPitch() != s.origin.getPitch()) p.setRotation(s.origin.getYaw(), s.origin.getPitch());
            }
        }
    }

    /** An invisible, unsaved item_display only this player's client knows about. */
    private ItemDisplay helper(Player p, Location at) {
        ItemDisplay d = p.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.getPersistentDataContainer().set(plugin.keys().helper, PersistentDataType.BYTE, (byte) 1);
        });
        p.showEntity(plugin, d);
        return d;
    }

    // ------------------------------------------------------------------ opening by right-click

    @EventHandler(priority = EventPriority.NORMAL)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (!plugin.cards().isCard(item)) return;
        Player p = e.getPlayer();
        e.setCancelled(true);
        if (isOpen(p)) return;
        if (!p.hasPermission("idcard.use")) {
            plugin.lang().send(p, "card.no-permission");
            return;
        }
        CardData data = plugin.cards().read(item);
        if (data.owner() == null) {
            plugin.lang().send(p, "card.unreadable");
            return;
        }
        // one click sends a packet per hand; the first one that reaches here opens the card
        open(p, data.owner());
    }

    // ------------------------------------------------------------------ closing and blocking while open

    /** The first ticks after opening belong to the click that opened it. */
    private boolean settled(Session s) {
        return Bukkit.getCurrentTick() - s.openedTick >= 4;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwing(PlayerArmSwingEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        if (settled(s)) close(e.getPlayer(), CloseReason.CLICK);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        if ((e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK) && e.getHand() == EquipmentSlot.HAND && settled(s)) {
            close(e.getPlayer(), CloseReason.CLICK);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        if (settled(s)) close(e.getPlayer(), CloseReason.CLICK);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (isOpen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent e) {
        if (isOpen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockDamage(BlockDamageEvent e) {
        if (isOpen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (isOpen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && isOpen(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && isOpen(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHeld(PlayerItemHeldEvent e) {
        if (isOpen(e.getPlayer())) e.setCancelled(true); // the server answers a refused scroll with the old slot
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isOpen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (isOpen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && isOpen(p)) e.setCancelled(true);
    }

    /** Before the camera lock takes hold (2 ticks) and in the fallback: no steps. Looking around is handled by the lock. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || s.seated || !e.hasChangedPosition()) return;
        Location to = e.getFrom().clone();
        to.setYaw(e.getTo().getYaw());
        to.setPitch(e.getTo().getPitch());
        e.setTo(to);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Session s = sessions.get(p.getUniqueId());
        if (s != null && s.seated && e.getDismounted().equals(s.seat)) e.setCancelled(true); // sneaking does not get you out
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && isOpen(p) && plugin.settings().uiCloseOnDamage) close(p, CloseReason.DAMAGE);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        close(e.getEntity(), CloseReason.DEATH);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        close(e.getPlayer(), CloseReason.QUIT);
        lastClosed.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || s.teleporting) return;
        // Refusing a step in onMove makes the server "teleport" the player back by a few centimetres, and that
        // fires this event too. Only a real change of place closes the card.
        Location from = e.getFrom(), to = e.getTo();
        if (from.getWorld() == to.getWorld() && from.distanceSquared(to) < 4) return;
        close(e.getPlayer(), CloseReason.TELEPORT);
    }

    /** Shift closes the card as well: the sneak key still reaches the server while the camera is locked. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSneak(PlayerToggleSneakEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null && e.isSneaking() && settled(s)) close(e.getPlayer(), CloseReason.CLICK);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        close(e.getPlayer(), CloseReason.WORLD);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        close(e.getPlayer(), CloseReason.GAMEMODE);
    }

    /** After a crash or kill -9: the marker survived in the player file, the anchor and the camera did not. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.getPersistentDataContainer().has(plugin.keys().uiOpen)) return;
        p.getPersistentDataContainer().remove(plugin.keys().uiOpen);
        if (p.getVehicle() != null && p.getVehicle().getPersistentDataContainer().has(plugin.keys().helper)) p.leaveVehicle();
        plugin.getLogger().info(p.getName() + " had the card UI open when the server stopped; state restored");
    }

    /** Helper entities are not saved, but a /reload without onDisable can leave them in loaded worlds. */
    public int sweepHelpers() {
        int n = 0;
        for (var w : Bukkit.getWorlds()) {
            for (Entity d : w.getEntities()) {
                if (d.getPersistentDataContainer().has(plugin.keys().helper)) {
                    d.remove();
                    n++;
                }
            }
        }
        return n;
    }
}
