package fi.alavesa.idcards.util;

import fi.alavesa.idcards.IdCardsPlugin;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Builds the plugin's non-card items: paper with an item model from the pack and a type tag. */
public final class Items {

    private Items() {
    }

    /** @param type the tag value ("scanner", "computer", "linktool"); name and lore come from lang item.&lt;type&gt;.* */
    public static ItemStack tagged(IdCardsPlugin plugin, String type, String model) {
        ItemStack item = ItemStack.of(Material.PAPER);
        item.setData(DataComponentTypes.ITEM_MODEL, Key.key("idcards", model));
        item.setData(DataComponentTypes.ITEM_NAME, plugin.lang().get("item." + type + ".name"));
        item.lore(List.of(plugin.lang().get("item." + type + ".lore").decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)));
        item.editPersistentDataContainer(pdc -> pdc.set(plugin.keys().itemType, PersistentDataType.STRING, type));
        return item;
    }

    /** A bare item that only shows a model (for item displays). */
    public static ItemStack model(String model) {
        ItemStack item = ItemStack.of(Material.PAPER);
        item.setData(DataComponentTypes.ITEM_MODEL, Key.key("idcards", model));
        return item;
    }
}
