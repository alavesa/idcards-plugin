package fi.alavesa.idcards.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/** Every persistent-data key the plugin writes. */
public final class Keys {

    /** On every plugin item: "card", "scanner", "computer", "linktool". */
    public final NamespacedKey itemType;
    /** On cards: the owner's UUID, the card's own id, when it was issued, and the server's signature over the three. */
    public final NamespacedKey cardOwner, cardId, cardIssued, cardSignature;
    /** On the client-only stand-in items shown while presenting; such an item must never exist in an inventory. */
    public final NamespacedKey fake;
    /** On block entities: the id of the placed block they belong to. */
    public final NamespacedKey blockId;
    /** On players: set while the card UI is open, so a crash cannot leave them frozen (cleared on join). */
    public final NamespacedKey uiOpen;
    /** On UI helper entities (camera anchor, seat). */
    public final NamespacedKey helper;
    /** Attribute modifiers of the mount-style freeze. */
    public final NamespacedKey freezeModifier;
    /** On players who chose the plain-text card (/idcard plain). */
    public final NamespacedKey plain;

    public Keys(Plugin plugin) {
        itemType = new NamespacedKey(plugin, "item");
        cardOwner = new NamespacedKey(plugin, "owner");
        cardId = new NamespacedKey(plugin, "card_id");
        cardIssued = new NamespacedKey(plugin, "issued");
        cardSignature = new NamespacedKey(plugin, "signature");
        fake = new NamespacedKey(plugin, "fake");
        blockId = new NamespacedKey(plugin, "block");
        uiOpen = new NamespacedKey(plugin, "ui_open");
        helper = new NamespacedKey(plugin, "helper");
        freezeModifier = new NamespacedKey(plugin, "freeze");
        plain = new NamespacedKey(plugin, "plain");
    }

    /** The plugin item type of a stack, or null for everything else. */
    public @Nullable String itemType(@Nullable ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return null;
        return item.getPersistentDataContainer().get(itemType, PersistentDataType.STRING);
    }

    public boolean isFake(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.hasItemMeta() && item.getPersistentDataContainer().has(fake);
    }
}
