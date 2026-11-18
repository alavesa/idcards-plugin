package fi.alavesa.idcards.block;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * One placed custom block: the persistent data (a row of the blocks table) plus, while its chunk is loaded, the
 * entities that show it. Positions inside the block are given in MODEL PIXELS (0..16 per axis, the same numbers as in
 * the block's model json), with the model's front on its north side; {@link #local} turns them into world positions
 * for this block's facing.
 */
public final class PlacedBlock {

    public final int id;
    public final BlockType type;
    public final String worldName;
    public final UUID worldUid;
    public final int x, y, z;
    public final BlockFace facing;
    public final Mount mount;
    private @Nullable String name;

    /** The entities showing this block (empty while its chunk is not loaded). Managed by {@link BlockManager}. */
    final List<Entity> entities = new ArrayList<>();
    boolean spawned;
    boolean removed;
    /** Whatever the block type keeps per block while it is spawned (animation state, screen lines). */
    public @Nullable Object runtime;

    public PlacedBlock(int id, BlockType type, String worldName, UUID worldUid, int x, int y, int z, BlockFace facing,
                       Mount mount, @Nullable String name) {
        this.id = id;
        this.type = type;
        this.worldName = worldName;
        this.worldUid = worldUid;
        this.x = x;
        this.y = y;
        this.z = z;
        this.facing = facing;
        this.mount = mount;
        this.name = name;
    }

    public @Nullable World world() {
        return Bukkit.getWorld(worldUid);
    }

    public boolean spawned() {
        return spawned;
    }

    public boolean removed() {
        return removed;
    }

    /** The block cell; only call while the world is loaded. */
    public Block block() {
        return world().getBlockAt(x, y, z);
    }

    public Location center() {
        return new Location(world(), x + 0.5, y + 0.5, z + 0.5);
    }

    /** Entity yaw that looks the way the block faces. */
    public float yaw() {
        return switch (facing) {
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> -90f;
            default -> 0f;
        };
    }

    /** World position of a point given in model pixels; the returned location looks along the block's facing. */
    public Location local(double px, double py, double pz) {
        double dx = px / 16.0 - 0.5, dz = pz / 16.0 - 0.5, wx, wz;
        switch (facing) {
            case EAST -> { wx = -dz; wz = dx; }
            case SOUTH -> { wx = -dx; wz = -dz; }
            case WEST -> { wx = dz; wz = -dx; }
            default -> { wx = dx; wz = dz; }
        }
        return new Location(world(), x + 0.5 + wx, y + py / 16.0, z + 0.5 + wz, yaw(), 0f);
    }

    /** The custom name, or a short generated one such as SCN-0012. */
    public String label() {
        return name != null && !name.isBlank() ? name : type.labelPrefix() + "-" + String.format("%04d", id);
    }

    public @Nullable String customName() {
        return name;
    }

    void customName(@Nullable String name) {
        this.name = name;
    }

    public double distanceSquared(Location l) {
        if (l.getWorld() == null || !l.getWorld().getUID().equals(worldUid)) return Double.MAX_VALUE;
        double dx = l.getX() - (x + 0.5), dy = l.getY() - (y + 0.5), dz = l.getZ() - (z + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }
}
