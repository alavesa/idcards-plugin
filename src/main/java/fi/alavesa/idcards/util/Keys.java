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
    /** On the stand-in items IdCards 1.0 showed while presenting; any that is found is deleted. */
    public final NamespacedKey fake;
    /** On block entities: the id of the placed block they belong to. */
    public final NamespacedKey blockId;
    /** On the display entities of a held or presented card (never saved; swept if a reload leaves them behind). */
    public final NamespacedKey helper;

    public Keys(Plugin plugin) {
        itemType = new NamespacedKey(plugin, "item");
        cardOwner = new NamespacedKey(plugin, "owner");
        cardId = new NamespacedKey(plugin, "card_id");
        cardIssued = new NamespacedKey(plugin, "issued");
        cardSignature = new NamespacedKey(plugin, "signature");
        fake = new NamespacedKey(plugin, "fake");
        blockId = new NamespacedKey(plugin, "block");
        helper = new NamespacedKey(plugin, "helper");
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
