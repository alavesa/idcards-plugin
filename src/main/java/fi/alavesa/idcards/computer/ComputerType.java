package fi.alavesa.idcards.computer;

import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.block.BlockManager;
import fi.alavesa.idcards.block.BlockType;
import fi.alavesa.idcards.block.Mount;
import fi.alavesa.idcards.block.PlacedBlock;
import fi.alavesa.idcards.link.LinkService;
import fi.alavesa.idcards.ui.Layout;
import fi.alavesa.idcards.util.Items;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The scanner computer: a desk terminal (solid: its cell holds a barrier) whose screen shows the latest scan. */
public final class ComputerType extends BlockType {

    private final IdCardsPlugin plugin;
    private final Box hitbox;

    public ComputerType(IdCardsPlugin plugin) {
        this.plugin = plugin;
        Layout.Box b = plugin.layout().box("computer.hitbox");
        hitbox = new Box(b.x0(), b.y0(), b.z0(), b.x1(), b.y1(), b.z1());
    }

    @Override
    public String id() {
        return LinkService.COMPUTER;
    }

    @Override
    public String labelPrefix() {
        return "CMP";
    }

    @Override
    public boolean baseBlock() {
        return true;
    }

    @Override
    public boolean allows(Mount mount) {
        return mount == Mount.FLOOR;
    }

    @Override
    public boolean needsSupport() {
        return false;
    }

    @Override
    public String placePermission() {
        return "idcard.computer.place";
    }

    @Override
    public String breakPermission() {
        return "idcard.computer.break";
    }

    @Override
    public ItemStack createItem() {
        return Items.tagged(plugin, id(), "computer");
    }

    @Override
    public Box hitbox(Mount mount) {
        return hitbox;
    }

    @Override
    public void spawn(PlacedBlock block, BlockManager.Spawner spawner) {
        spawner.itemDisplay(block.local(8, 8, 8), Items.model("computer"));
        block.runtime = new Screen(plugin, block, spawner);
        plugin.computers().restore(block);
    }

    @Override
    public void despawn(PlacedBlock block) {
        if (block.runtime instanceof Screen screen) screen.cancelTimeout();
    }

    @Override
    public void interact(Player player, PlacedBlock block) {
        if (plugin.links().isTool(player.getInventory().getItemInMainHand())) {
            plugin.links().click(player, block);
            return;
        }
        plugin.computers().openLog(player, block, 0);
    }

    @Override
    public void placed(PlacedBlock block, Player by) {
        plugin.links().autoLink(block, by);
    }

    @Override
    public void removed(PlacedBlock block) {
        plugin.links().unlinkAll(block);
        plugin.computers().forget(block);
    }
}
