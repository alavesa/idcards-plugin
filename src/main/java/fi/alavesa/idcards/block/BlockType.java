package fi.alavesa.idcards.block;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A kind of placeable custom block (card scanner, scanner computer). Everything the two have in common lives in
 * {@link BlockManager}: placing with protection events, persistence, chunk load and unload, breaking, dropping,
 * self-healing. A type only says what it looks like and what a click does.
 */
public abstract class BlockType {

    /** An axis-aligned box in model pixels (0..16, front = north). */
    public record Box(double x0, double y0, double z0, double x1, double y1, double z1) {
    }

    /** Storage key, also the value of the item's type tag: "scanner", "computer". */
    public abstract String id();

    /** Prefix of generated names: SCN-0012. */
    public abstract String labelPrefix();

    /** True: the cell holds a barrier block (collision, cannot be walked through). False: the cell stays empty. */
    public abstract boolean baseBlock();

    public abstract boolean allows(Mount mount);

    /** True: the block needs a solid block under it (floor) or behind it (wall) and drops when that goes away. */
    public abstract boolean needsSupport();

    public abstract String placePermission();

    public abstract String breakPermission();

    /** A fresh item that places this block. */
    public abstract ItemStack createItem();

    /** The clickable box. It becomes one Interaction entity, which is square: the larger of width and depth counts. */
    public abstract Box hitbox(Mount mount);

    /** Create the entities through {@code spawner}; they are tracked and removed by the manager. */
    public abstract void spawn(PlacedBlock block, BlockManager.Spawner spawner);

    /** Its chunk is unloading or the block is being removed: stop tasks, drop references. Entities are removed for you. */
    public void despawn(PlacedBlock block) {
    }

    /** Right-click on the block. */
    public void interact(Player player, PlacedBlock block) {
    }

    /** Just placed (already stored and spawned). */
    public void placed(PlacedBlock block, Player by) {
    }

    /** Gone for good (already despawned and deleted). */
    public void removed(PlacedBlock block) {
    }
}
