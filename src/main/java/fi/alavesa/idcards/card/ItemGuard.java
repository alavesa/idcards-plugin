package fi.alavesa.idcards.card;

import fi.alavesa.idcards.IdCardsPlugin;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Keeps plugin items out of places where vanilla would consume, copy or transform them.
 *
 * <ul>
 *   <li>No plugin item (card, scanner, computer, link tool) can be put into a crafting
 *       grid, furnace, anvil, grindstone, loom, cartography table, brewing stand, villager trade or any other
 *       workstation, by clicking, dragging, shift-clicking, number keys or hoppers. Storage containers are fine,
 *       for cards only when card.allow-containers is on.</li>
 *   <li>Cards follow card.allow-drop and card.on-death.</li>
 *   <li>A card is a spear underneath and must not hurt: hits and damage by a player holding one are cancelled.</li>
 *   <li>Leftovers of IdCards 1.0 are tidied on join: paper cards become current cards, stand-in items are deleted.</li>
 * </ul>
 */
public final class ItemGuard implements Listener {

    private static final Set<InventoryType> STORAGE = EnumSet.of(InventoryType.CHEST, InventoryType.BARREL, InventoryType.SHULKER_BOX,
            InventoryType.ENDER_CHEST, InventoryType.DISPENSER, InventoryType.DROPPER, InventoryType.HOPPER);
    private static final Set<InventoryType> OWN = EnumSet.of(InventoryType.PLAYER, InventoryType.CREATIVE, InventoryType.CRAFTING);

    private final IdCardsPlugin plugin;

    public ItemGuard(IdCardsPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean ours(ItemStack item) {
        return plugin.keys().itemType(item) != null;
    }

    /** May this item sit in an inventory of this type? */
    private boolean allowedIn(ItemStack item, InventoryType type) {
        if (!ours(item) || OWN.contains(type)) return true;
        if (!STORAGE.contains(type)) return false;
        return !plugin.cards().isCard(item) || plugin.settings().cardAllowContainers;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (plugin.keys().isFake(e.getCurrentItem())) {
            e.setCancelled(true);
            e.setCurrentItem(null);
            return;
        }
        InventoryType top = e.getView().getTopInventory().getType();
        boolean inTop = e.getClickedInventory() != null && e.getClickedInventory() == e.getView().getTopInventory();
        ItemStack moving = null;
        if (inTop) {
            // placing the cursor item, or swapping a hotbar / off-hand item in
            if (e.getClick() == ClickType.NUMBER_KEY) moving = e.getWhoClicked().getInventory().getItem(e.getHotbarButton());
            else if (e.getClick() == ClickType.SWAP_OFFHAND) moving = e.getWhoClicked().getInventory().getItemInOffHand();
            else moving = e.getCursor();
        } else if (e.isShiftClick()) {
            moving = e.getCurrentItem(); // shift-click from the player's side goes into the top inventory
        }
        // the 2x2 grid of the player's own inventory is of type CRAFTING too: slots 1-4 are the grid
        if (top == InventoryType.CRAFTING && inTop && e.getSlotType() == InventoryType.SlotType.CRAFTING && moving != null && ours(moving)) {
            e.setCancelled(true);
            return;
        }
        if (moving != null && !allowedIn(moving, top)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        ItemStack item = e.getOldCursor();
        if (!ours(item)) return;
        InventoryType top = e.getView().getTopInventory().getType();
        int topSize = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < topSize && (!allowedIn(item, top) || top == InventoryType.CRAFTING)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent e) {
        if (!allowedIn(e.getItem(), e.getDestination().getType())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent e) {
        if (!allowedIn(e.getItem().getItemStack(), e.getInventory().getType())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack item : e.getInventory().getMatrix()) {
            if (item != null && ours(item)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        // paper is not placeable; this covers a server whose items were re-based onto something that is.
        // The framework's own placing fires this event with the placer item in hand and must pass.
        String type = plugin.keys().itemType(e.getItemInHand());
        if (type != null && plugin.blocks().type(type) == null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        ItemStack item = e.getItemDrop().getItemStack();
        if (plugin.keys().isFake(item)) {
            e.getItemDrop().remove();
            Bukkit.getScheduler().runTask(plugin, e.getPlayer()::updateInventory);
        } else if (plugin.cards().isCard(item) && !plugin.settings().cardAllowDrop) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(plugin.keys()::isFake);
        String mode = plugin.settings().cardOnDeath;
        if (mode.equals("drop") || e.getKeepInventory()) return;
        var it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack item = it.next();
            if (!plugin.cards().isCard(item)) continue;
            it.remove();
            if (mode.equals("keep")) e.getItemsToKeep().add(item);
        }
    }

    /** A creative client writes its own idea of a slot to the server: never accept a stand-in that way. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCreative(InventoryCreativeEvent e) {
        if (plugin.keys().isFake(e.getCursor())) {
            e.setCancelled(true);
            Player p = (Player) e.getWhoClicked();
            Bukkit.getScheduler().runTask(plugin, p::updateInventory);
        }
    }

    /** A left click on somebody with a card in the hand is not an attack. Our own blocks handle their clicks themselves. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        if (plugin.cards().isCard(e.getPlayer().getInventory().getItemInMainHand()) && plugin.blocks().of(e.getAttacked()) == null) e.setCancelled(true);
    }

    /** Belt and braces for anything the spear could still do (a held-out spear touches what it is pushed into). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && plugin.cards().isCard(p.getInventory().getItemInMainHand())) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        PlayerInventory inv = e.getPlayer().getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (plugin.keys().isFake(item)) inv.setItem(i, null);
            else if (item != null && item.getType() != CardService.BASE && plugin.cards().isCard(item)) inv.setItem(i, plugin.cards().upgrade(item));
        }
        if (plugin.settings().cardGiveOnJoin && e.getPlayer().hasPermission("idcard.use") && !plugin.cards().hasOwnCard(e.getPlayer())) {
            plugin.cards().give(e.getPlayer(), e.getPlayer().getUniqueId());
        }
    }
}
