package fi.alavesa.idcards.presenting;

import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.api.CardPresentEvent;
import fi.alavesa.idcards.card.CardData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Holding a card and holding it out.
 *
 * <p>The card never takes control of the player: no camera, no freezing, no fake items. Everything is read from what
 * the player does with an ordinary item, once per tick:
 * <ul>
 *   <li><b>The card is the item in the main hand:</b> the holder sees their own card, with their details, in front of
 *       them ({@link CardProp}). They walk, jump, look around and use anything else as usual.</li>
 *   <li><b>The right mouse button is held:</b> the card is a spear underneath, so vanilla plays the spear's
 *       hold-out animation on the player for everybody (a real animation, not a trick), and a hologram of the card
 *       with the owner's face, name and prefix appears in front of them for the others. The card's spear does no
 *       damage, knockback or dismounting (see CardService, ItemGuard).</li>
 *   <li><b>The button is released,</b> or the card leaves the hand: the hologram goes and the holder's card turns its
 *       front back to them.</li>
 * </ul>
 * Main thread only. Cost per tick: one main-hand look per online player, and for players holding a card a handful of
 * comparisons; packets are only sent when something changes.
 */
public final class PresentService implements Listener {

    private static final class State {
        UUID owner;
        UUID cardId;
        CardProp prop;
        boolean presenting, refused, forced;
        int lastPresent = -1000;
    }

    private final IdCardsPlugin plugin;
    private final Map<UUID, State> holding = new HashMap<>();

    public PresentService(IdCardsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Starts the timer; call once from onEnable. */
    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public boolean isPresenting(Player p) {
        State s = holding.get(p.getUniqueId());
        return s != null && s.presenting;
    }

    /** presenter -> owner of the card they hold out. */
    public Map<UUID, UUID> owners() {
        Map<UUID, UUID> out = new HashMap<>();
        holding.forEach((id, s) -> {
            if (s.presenting) out.put(id, s.owner);
        });
        return out;
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = holding.get(p.getUniqueId());
            ItemStack hand = p.getInventory().getItemInMainHand();
            CardData data = hand.isEmpty() || p.isDead() || p.getGameMode() == GameMode.SPECTATOR ? null : plugin.cards().readHeld(p, hand);
            if (data == null || data.owner() == null || !p.hasPermission("idcard.use")) {
                if (s != null) drop(p);
                continue;
            }
            if (s != null && (!data.owner().equals(s.owner) || !data.cardId().equals(s.cardId) || !s.prop.valid())) {
                drop(p); // another card in the same hand, or its entities were removed (/kill)
                s = null;
            }
            if (s == null) {
                s = new State();
                s.owner = data.owner();
                s.cardId = data.cardId();
                s.prop = new CardProp(plugin, p, plugin.views().of(s.owner));
                holding.put(p.getUniqueId(), s);
            }
            // forced: /idcard test hold. A client that is not really pressing the button ends a server-started use at once.
            if (s.forced && !p.hasActiveItem()) p.startUsingItem(EquipmentSlot.HAND);
            boolean using = s.forced || (p.hasActiveItem() && p.getActiveItemHand() == EquipmentSlot.HAND);
            if (!using) s.refused = false;
            if (using && !s.presenting && !s.refused) {
                if (!mayPresent(p, s, data)) {
                    s.refused = true; // until the button is released
                    p.clearActiveItem();
                } else {
                    s.presenting = true;
                    s.lastPresent = Bukkit.getCurrentTick();
                    s.prop.show(true);
                }
            } else if (!using && s.presenting) {
                s.presenting = false;
                s.prop.show(false);
            }
            s.prop.tick();
        }
    }

    private boolean mayPresent(Player p, State s, CardData data) {
        if (!p.hasPermission("idcard.present")) return false;
        if (Bukkit.getCurrentTick() - s.lastPresent < plugin.settings().presentCooldownTicks) return false; // rate limit
        CardPresentEvent event = new CardPresentEvent(p, data.owner(), data.valid());
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    private void drop(Player p) {
        State s = holding.remove(p.getUniqueId());
        if (s != null) s.prop.remove();
    }

    /** For /idcard test hold: behaves as if the right mouse button were held until called again. Returns the new state. */
    public boolean toggleForced(Player p) {
        State s = holding.get(p.getUniqueId());
        if (s == null) return false;
        s.forced = !s.forced;
        if (!s.forced) p.clearActiveItem();
        return s.forced;
    }

    public void stop(Player p) {
        drop(p);
    }

    public void stopAll() {
        for (UUID id : List.copyOf(holding.keySet())) {
            State s = holding.remove(id);
            if (s != null) s.prop.remove();
        }
    }

    /** A name, skin or prefix changed: redraw the cards that show this owner. */
    public void refresh(UUID owner) {
        for (State s : holding.values()) {
            if (s.owner.equals(owner)) s.prop.rewrite(plugin.views().of(owner));
        }
    }

    /** Unsaved entities cannot come back from disk, but a /reload without onDisable can leave them in loaded worlds. */
    public int sweep() {
        int n = 0;
        for (var w : Bukkit.getWorlds()) {
            for (var e : w.getEntities()) {
                if (e.getPersistentDataContainer().has(plugin.keys().helper)) {
                    e.remove();
                    n++;
                }
            }
        }
        return n;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        drop(e.getPlayer());
    }

    /** The entities live in the old world: make new ones in the new one on the next tick. */
    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        drop(e.getPlayer());
    }

    public @Nullable UUID presentedOwner(Player p) {
        State s = holding.get(p.getUniqueId());
        return s != null && s.presenting ? s.owner : null;
    }
}
