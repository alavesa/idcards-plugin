package fi.alavesa.idcards.scanner;

import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.block.BlockManager;
import fi.alavesa.idcards.block.BlockType;
import fi.alavesa.idcards.block.Mount;
import fi.alavesa.idcards.block.PlacedBlock;
import fi.alavesa.idcards.link.LinkService;
import fi.alavesa.idcards.ui.Layout;
import fi.alavesa.idcards.util.Items;
import java.util.Locale;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The card scanner: a small reader that hangs on a wall or stands on a post, with a slot and a status light.
 * The light is part of the model: each state is its own model, and changing state swaps the displayed item.
 */
public final class ScannerType extends BlockType {

    public enum Light { IDLE, SCANNING, GRANTED, DENIED }

    /** Per spawned scanner. */
    public static final class Runtime {
        ItemDisplay model;
        @Nullable ItemDisplay card;
        Light light = Light.IDLE;
        int busyUntil;
        /** Bumped whenever the scanner is hidden: scheduled animation steps of an older life do nothing. */
        int generation;
    }

    private final IdCardsPlugin plugin;
    private final Box wall, floor;

    public ScannerType(IdCardsPlugin plugin) {
        this.plugin = plugin;
        Layout.Box w = plugin.layout().box("scanner.wall.hitbox"), f = plugin.layout().box("scanner.floor.hitbox");
        wall = new Box(w.x0(), w.y0(), w.z0(), w.x1(), w.y1(), w.z1());
        floor = new Box(f.x0(), f.y0(), f.z0(), f.x1(), f.y1(), f.z1());
    }

    @Override
    public String id() {
        return LinkService.SCANNER;
    }

    @Override
    public String labelPrefix() {
        return "SCN";
    }

    @Override
    public boolean baseBlock() {
        return false;
    }

    @Override
    public boolean allows(Mount mount) {
        return true;
    }

    @Override
    public boolean needsSupport() {
        return true;
    }

    @Override
    public String placePermission() {
        return "idcard.scanner.place";
    }

    @Override
    public String breakPermission() {
        return "idcard.scanner.break";
    }

    @Override
    public ItemStack createItem() {
        return Items.tagged(plugin, id(), "scanner");
    }

    @Override
    public Box hitbox(Mount mount) {
        return mount == Mount.WALL ? wall : floor;
    }

    static ItemStack modelItem(PlacedBlock block, Light light) {
        return Items.model("scanner_" + block.mount.name().toLowerCase(Locale.ROOT) + "_" + light.name().toLowerCase(Locale.ROOT));
    }

    @Override
    public void spawn(PlacedBlock block, BlockManager.Spawner spawner) {
        Runtime rt = new Runtime();
        rt.model = spawner.itemDisplay(block.local(8, 8, 8), modelItem(block, Light.IDLE));
        block.runtime = rt;
    }

    @Override
    public void despawn(PlacedBlock block) {
        if (block.runtime instanceof Runtime rt) rt.generation++;
    }

    @Override
    public void interact(Player player, PlacedBlock block) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (plugin.links().isTool(main)) {
            plugin.links().click(player, block);
            return;
        }
        ItemStack card = plugin.cards().held(player);
        if (card == null) return; // no message: an empty hand on a scanner is not worth a chat line
        player.clearActiveItem(); // the click must not also start holding the card out
        plugin.scans().scan(player, block, card);
    }

    @Override
    public void placed(PlacedBlock block, Player by) {
        plugin.links().autoLink(block, by);
    }

    @Override
    public void removed(PlacedBlock block) {
        plugin.links().unlinkAll(block);
    }
}
