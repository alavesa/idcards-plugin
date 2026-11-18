package fi.alavesa.idcards.block;

import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.config.Lang;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * The custom-block framework shared by the card scanner and the scanner computer.
 *
 * <p><b>Technique.</b> Paper has no custom blocks, so a placed block is: a row in the database (the source of
 * truth), an item_display showing the model, one Interaction entity as the click box, whatever else its type spawns
 * (text displays for the screen), and for types that want collision a barrier block in the cell. All entities are
 * NON-persistent: Minecraft never saves them, so a crash, a kill command or a world edit cannot leave orphans or
 * duplicates behind. They are created from the database when their chunk loads and removed when it unloads.
 *
 * <p><b>Self-healing.</b> An entity of ours that disappears while its chunk is loaded (/kill, a clear plugin) is
 * put back on the next tick. Blocks whose cell or support changed (broken wall, explosion, piston, water, a world
 * edit that removed the barrier) are checked the tick after the change, and every loaded block is checked again on
 * a slow timer; a block that is no longer valid drops its item and is removed.
 *
 * <p>Main thread only.
 */
public final class BlockManager implements Listener {

    /** Hands out entities that are tagged, non-persistent and tracked for one block. */
    public final class Spawner {
        private final PlacedBlock block;

        private Spawner(PlacedBlock block) {
            this.block = block;
        }

        public ItemDisplay itemDisplay(Location at, ItemStack item) {
            return at.getWorld().spawn(at, ItemDisplay.class, d -> {
                prepare(d);
                d.setItemStack(item);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            });
        }

        public TextDisplay textDisplay(Location at) {
            return at.getWorld().spawn(at, TextDisplay.class, this::prepare);
        }

        private void prepare(Entity e) {
            e.setPersistent(false);
            e.addScoreboardTag(ENTITY_TAG);
            e.getPersistentDataContainer().set(plugin.keys().blockId, PersistentDataType.INTEGER, block.id);
            if (e instanceof Display d) d.setViewRange((float) plugin.settings().blockViewRange);
            block.entities.add(e);
            byEntity.put(e.getUniqueId(), block);
        }
    }

    /** A spawner for entities added later (a swipe animation); they belong to the block like the first ones. */
    public Spawner spawnerFor(PlacedBlock block) {
        return new Spawner(block);
    }

    /** Removes one entity of a block before the block itself goes. */
    public void discard(Entity e) {
        PlacedBlock b = byEntity.remove(e.getUniqueId());
        if (b != null) b.entities.remove(e);
        e.remove();
    }

    /** The click box of a spawned block in world coordinates, or null. */
    public org.bukkit.util.@Nullable BoundingBox hitbox(PlacedBlock b) {
        for (Entity e : b.entities) if (e instanceof Interaction && e.isValid()) return e.getBoundingBox();
        return null;
    }

    private record Pos(UUID world, int x, int y, int z) {
    }

    private record BreakProgress(int block, int hits, int tick) {
    }

    public static final String ENTITY_TAG = "idcards.block";

    private final IdCardsPlugin plugin;
    private final Map<String, BlockType> types = new LinkedHashMap<>();
    private final Map<Integer, PlacedBlock> byId = new HashMap<>();
    private final Map<Pos, PlacedBlock> byPos = new HashMap<>();
    private final Map<UUID, Map<Long, List<PlacedBlock>>> byChunk = new HashMap<>();
    private final Map<UUID, PlacedBlock> byEntity = new HashMap<>();
    private final Set<PlacedBlock> dirty = new HashSet<>();
    private final Map<UUID, BreakProgress> breaking = new HashMap<>();
    private final Map<UUID, Integer> lastClick = new HashMap<>();

    public BlockManager(IdCardsPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(BlockType type) {
        types.put(type.id(), type);
    }

    public @Nullable BlockType type(String id) {
        return types.get(id);
    }

    /** The block type an item places, or null. */
    public @Nullable BlockType typeOf(@Nullable ItemStack item) {
        String tag = plugin.keys().itemType(item);
        return tag == null ? null : types.get(tag);
    }

    // ------------------------------------------------------------------ loading

    /** Reads every block from the database and shows those in loaded chunks. Call once all types are registered. */
    public void load() {
        // entities of ours that survived a /reload without onDisable; non-persistent ones cannot come from disk
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(ENTITY_TAG)) e.remove();
        }
        int unknownWorld = 0, purged = 0;
        List<PlacedBlock> rows = plugin.db().load("SELECT id, type, world, world_uid, x, y, z, facing, mount, name FROM blocks", rs -> {
            BlockType type = types.get(rs.getString(2));
            if (type == null) return null;
            return new PlacedBlock(rs.getInt(1), type, rs.getString(3), UUID.fromString(rs.getString(4)), rs.getInt(5), rs.getInt(6),
                    rs.getInt(7), BlockFace.valueOf(rs.getString(8)), Mount.valueOf(rs.getString(9)), rs.getString(10));
        });
        for (PlacedBlock b : rows) {
            if (b == null) continue;
            World byName = Bukkit.getWorld(b.worldName);
            if (b.world() == null && byName != null) {
                // a world of that name exists but it is a different world: the old one was deleted and made anew
                plugin.db().write("DELETE FROM blocks WHERE id = ?", b.id);
                plugin.db().write("DELETE FROM links WHERE scanner = ? OR computer = ?", b.id, b.id);
                purged++;
                continue;
            }
            if (b.world() == null) unknownWorld++;
            index(b);
        }
        if (purged > 0) plugin.getLogger().info(purged + " placed blocks belonged to a deleted world and were forgotten");
        if (unknownWorld > 0) plugin.getLogger().info(unknownWorld + " placed blocks are in worlds that are not loaded; they appear when their world loads");
        for (World w : Bukkit.getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) spawnChunk(c);
        }
        int interval = Math.max(20, plugin.settings().blockValidateInterval);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (PlacedBlock b : byId.values()) if (b.spawned) dirty.add(b);
        }, interval, interval);
        Bukkit.getScheduler().runTaskTimer(plugin, this::validateDirty, 1L, 1L);
    }

    /** Removes every entity (plugin disable). The blocks stay in the database. */
    public void shutdown() {
        for (PlacedBlock b : byId.values()) despawn(b);
    }

    private void index(PlacedBlock b) {
        byId.put(b.id, b);
        byPos.put(new Pos(b.worldUid, b.x, b.y, b.z), b);
        byChunk.computeIfAbsent(b.worldUid, k -> new HashMap<>()).computeIfAbsent(Chunk.getChunkKey(b.x >> 4, b.z >> 4), k -> new ArrayList<>()).add(b);
    }

    private void unindex(PlacedBlock b) {
        byId.remove(b.id);
        byPos.remove(new Pos(b.worldUid, b.x, b.y, b.z));
        Map<Long, List<PlacedBlock>> chunks = byChunk.get(b.worldUid);
        if (chunks != null) {
            List<PlacedBlock> list = chunks.get(Chunk.getChunkKey(b.x >> 4, b.z >> 4));
            if (list != null) list.remove(b);
        }
    }

    // ------------------------------------------------------------------ queries

    public @Nullable PlacedBlock byId(int id) {
        return byId.get(id);
    }

    public Collection<PlacedBlock> all() {
        return byId.values();
    }

    public @Nullable PlacedBlock at(Block block) {
        return byPos.get(new Pos(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()));
    }

    public @Nullable PlacedBlock of(Entity entity) {
        return byEntity.get(entity.getUniqueId());
    }

    /** Spawned blocks of one type within {@code radius} of a location. */
    public List<PlacedBlock> near(Location l, double radius, BlockType type) {
        List<PlacedBlock> out = new ArrayList<>();
        Map<Long, List<PlacedBlock>> chunks = l.getWorld() == null ? null : byChunk.get(l.getWorld().getUID());
        if (chunks == null) return out;
        int r = (int) Math.ceil(radius);
        for (int cx = (l.getBlockX() - r) >> 4; cx <= (l.getBlockX() + r) >> 4; cx++) {
            for (int cz = (l.getBlockZ() - r) >> 4; cz <= (l.getBlockZ() + r) >> 4; cz++) {
                List<PlacedBlock> list = chunks.get(Chunk.getChunkKey(cx, cz));
                if (list == null) continue;
                for (PlacedBlock b : list) {
                    if (b.type == type && b.spawned && b.distanceSquared(l) <= radius * radius) out.add(b);
                }
            }
        }
        return out;
    }

    public void rename(PlacedBlock b, @Nullable String name) {
        b.customName(name);
        plugin.db().write("UPDATE blocks SET name = ? WHERE id = ?", name, b.id);
    }

    // ------------------------------------------------------------------ spawn / despawn

    private void spawnChunk(Chunk c) {
        Map<Long, List<PlacedBlock>> chunks = byChunk.get(c.getWorld().getUID());
        List<PlacedBlock> list = chunks == null ? null : chunks.get(c.getChunkKey());
        if (list == null) return;
        for (PlacedBlock b : List.copyOf(list)) spawn(b);
    }

    private void spawn(PlacedBlock b) {
        if (b.spawned || b.removed || b.world() == null) return;
        b.spawned = true;
        try {
            Spawner spawner = new Spawner(b);
            BlockType.Box box = b.type.hitbox(b.mount);
            float width = (float) (Math.max(box.x1() - box.x0(), box.z1() - box.z0()) / 16.0), height = (float) ((box.y1() - box.y0()) / 16.0);
            Location at = b.local((box.x0() + box.x1()) / 2, box.y0(), (box.z0() + box.z1()) / 2);
            at.getWorld().spawn(at, Interaction.class, i -> {
                spawner.prepare(i);
                i.setInteractionWidth(width);
                i.setInteractionHeight(height);
                i.setResponsive(false);
            });
            b.type.spawn(b, spawner);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not show " + b.label() + " at " + b.x + "," + b.y + "," + b.z, e);
        }
    }

    private void despawn(PlacedBlock b) {
        if (!b.spawned) return;
        b.spawned = false;
        try {
            b.type.despawn(b);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Error while hiding " + b.label(), e);
        }
        for (Entity e : List.copyOf(b.entities)) {
            byEntity.remove(e.getUniqueId());
            e.remove();
        }
        b.entities.clear();
        b.runtime = null;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        spawnChunk(e.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        Map<Long, List<PlacedBlock>> chunks = byChunk.get(e.getWorld().getUID());
        List<PlacedBlock> list = chunks == null ? null : chunks.get(e.getChunk().getChunkKey());
        if (list != null) for (PlacedBlock b : list) despawn(b);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        for (Chunk c : e.getWorld().getLoadedChunks()) spawnChunk(c);
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent e) {
        Map<Long, List<PlacedBlock>> chunks = byChunk.get(e.getWorld().getUID());
        if (chunks != null) chunks.values().forEach(list -> list.forEach(this::despawn));
    }

    /** One of our entities vanished although we did not remove it: put the whole block back on the next tick. */
    @EventHandler
    public void onEntityRemove(EntityRemoveEvent e) {
        PlacedBlock b = byEntity.get(e.getEntity().getUniqueId());
        if (b == null || !b.spawned || e.getCause() == EntityRemoveEvent.Cause.UNLOAD || e.getCause() == EntityRemoveEvent.Cause.PLUGIN) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (b.removed || !b.spawned) return;
            despawn(b);
            if (b.world() != null && b.world().isChunkLoaded(b.x >> 4, b.z >> 4)) spawn(b);
        });
    }

    // ------------------------------------------------------------------ placing

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getItem() == null) return;
        BlockType type = typeOf(e.getItem());
        if (type == null) return;
        e.setCancelled(true); // the base item must never do its vanilla thing
        if (e.useItemInHand() == org.bukkit.event.Event.Result.DENY && e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return;
        Player p = e.getPlayer();
        if (debounce(p)) return;
        Lang lang = plugin.lang();
        if (!p.hasPermission(type.placePermission())) {
            lang.send(p, "block.no-permission-place");
            return;
        }
        BlockFace face = e.getBlockFace();
        Block clicked = e.getClickedBlock(), cell = clicked.getRelative(face);
        Mount mount;
        BlockFace facing;
        if (face == BlockFace.UP || face == BlockFace.DOWN || !type.allows(Mount.WALL)) {
            mount = Mount.FLOOR;
            facing = p.getFacing().getOppositeFace(); // the front looks at whoever placed it
            if (facing.getModY() != 0 || !facing.isCartesian()) facing = BlockFace.NORTH;
        } else {
            mount = Mount.WALL;
            facing = face;
        }
        if (!type.allows(mount) || (face == BlockFace.DOWN && type.needsSupport())) {
            lang.send(p, "block.cannot-place-here");
            return;
        }
        if (!cell.isEmpty() && !cell.isReplaceable() || cell.isLiquid() || at(cell) != null
                || cell.getY() < cell.getWorld().getMinHeight() || cell.getY() >= cell.getWorld().getMaxHeight()) {
            lang.send(p, "block.cannot-place-here");
            return;
        }
        Block support = mount == Mount.WALL ? cell.getRelative(facing.getOppositeFace()) : cell.getRelative(BlockFace.DOWN);
        if (type.needsSupport() && !support.getType().isSolid()) {
            lang.send(p, "block.needs-support");
            return;
        }
        // protection plugins decide through the ordinary event, exactly as for a vanilla block
        BlockState replaced = cell.getState();
        BlockPlaceEvent place = new BlockPlaceEvent(cell, replaced, clicked, e.getItem(), p, true, e.getHand() == null ? EquipmentSlot.HAND : e.getHand());
        Bukkit.getPluginManager().callEvent(place);
        if (place.isCancelled() || !place.canBuild()) return;

        PlacedBlock b = create(type, cell, facing, mount, p.getUniqueId());
        if (b == null) {
            lang.send(p, "block.cannot-place-here");
            return;
        }
        if (p.getGameMode() != GameMode.CREATIVE) e.getItem().subtract(1);
        cell.getWorld().playSound(b.center(), Sound.BLOCK_METAL_PLACE, 1f, 1.1f);
        p.swingHand(e.getHand() == null ? EquipmentSlot.HAND : e.getHand());
        type.placed(b, p);
    }

    /** Stores and shows a new block. Returns null when the database refused. */
    public @Nullable PlacedBlock create(BlockType type, Block cell, BlockFace facing, Mount mount, @Nullable UUID by) {
        long id;
        try {
            id = plugin.db().insert("INSERT INTO blocks (type, world, world_uid, x, y, z, facing, mount, placed_by, placed_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    type.id(), cell.getWorld().getName(), cell.getWorld().getUID().toString(), cell.getX(), cell.getY(), cell.getZ(),
                    facing.name(), mount.name(), by == null ? null : by.toString(), System.currentTimeMillis());
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not store a placed block", ex);
            return null;
        }
        if (id < 0) return null;
        PlacedBlock b = new PlacedBlock((int) id, type, cell.getWorld().getName(), cell.getWorld().getUID(), cell.getX(), cell.getY(), cell.getZ(), facing, mount, null);
        if (type.baseBlock()) cell.setType(Material.BARRIER, false);
        else if (!cell.isEmpty()) cell.setType(Material.AIR, false); // grass, snow layer
        index(b);
        spawn(b);
        return b;
    }

    // ------------------------------------------------------------------ clicking and breaking

    /** Both hands send their own packet for one click, and a held click repeats: one action per 4 ticks is enough. */
    private boolean debounce(Player p) {
        int now = Bukkit.getCurrentTick();
        Integer last = lastClick.put(p.getUniqueId(), now);
        return last != null && now - last < 4;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        PlacedBlock b = of(e.getRightClicked());
        if (b == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND || debounce(e.getPlayer())) return;
        b.type.interact(e.getPlayer(), b);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        PlacedBlock b = of(e.getAttacked());
        if (b == null) return;
        e.setCancelled(true);
        hit(e.getPlayer(), b);
    }

    /** A creative player can reach the barrier itself past the click box. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBarrierBreak(BlockBreakEvent e) {
        PlacedBlock b = at(e.getBlock());
        if (b != null && !breakingNow) {
            e.setCancelled(true);
            hit(e.getPlayer(), b);
        } else if (b == null) {
            touch(e.getBlock());
        }
    }

    private boolean breakingNow;

    /**
     * A left click on a block. Creative: gone at once. Survival: the configured number of hits in a row (each within
     * the window of the one before) breaks it, with crack particles and a hit sound per hit, which feels like
     * knocking a device off the wall rather than mining stone.
     */
    private void hit(Player p, PlacedBlock b) {
        if (!p.hasPermission(b.type.breakPermission())) {
            plugin.lang().send(p, "block.no-permission-break");
            return;
        }
        int now = Bukkit.getCurrentTick(), needed = p.getGameMode() == GameMode.CREATIVE ? 1 : Math.max(1, plugin.settings().blockBreakHits);
        BreakProgress before = breaking.get(p.getUniqueId());
        if (before != null && before.tick == now) return; // attack + block-break packet of the same click
        int hits = before != null && before.block == b.id && now - before.tick <= plugin.settings().blockBreakWindow ? before.hits + 1 : 1;
        Location c = b.center();
        if (hits < needed) {
            breaking.put(p.getUniqueId(), new BreakProgress(b.id, hits, now));
            c.getWorld().playSound(c, Sound.BLOCK_METAL_HIT, 0.8f, 0.9f + 0.1f * hits);
            c.getWorld().spawnParticle(Particle.BLOCK, c, 6, 0.2, 0.2, 0.2, Material.IRON_BLOCK.createBlockData());
            return;
        }
        breaking.remove(p.getUniqueId());
        breakingNow = true;
        try {
            BlockBreakEvent event = new BlockBreakEvent(b.block(), p);
            event.setDropItems(false);
            event.setExpToDrop(0);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return;
        } finally {
            breakingNow = false;
        }
        remove(b, p.getGameMode() != GameMode.CREATIVE);
    }

    /** Removes a block for good: entities, barrier, database row, links (through the type). */
    public void remove(PlacedBlock b, boolean drop) {
        if (b.removed) return;
        World w = b.world();
        despawn(b);
        b.removed = true;
        unindex(b);
        dirty.remove(b);
        plugin.db().write("DELETE FROM blocks WHERE id = ?", b.id);
        if (w != null) {
            Block cell = w.getBlockAt(b.x, b.y, b.z);
            if (b.type.baseBlock() && cell.getType() == Material.BARRIER) cell.setType(Material.AIR, false);
            Location c = new Location(w, b.x + 0.5, b.y + 0.5, b.z + 0.5);
            w.playSound(c, Sound.BLOCK_METAL_BREAK, 1f, 1f);
            w.spawnParticle(Particle.BLOCK, c, 20, 0.25, 0.25, 0.25, Material.IRON_BLOCK.createBlockData());
            if (drop) w.dropItemNaturally(c, b.type.createItem());
        }
        b.type.removed(b);
    }

    // ------------------------------------------------------------------ validity

    /** Something happened to this cell: check our blocks in it and around it on the next tick. */
    private void touch(Block block) {
        PlacedBlock here = at(block);
        if (here != null) dirty.add(here);
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            PlacedBlock n = at(block.getRelative(f));
            if (n != null) dirty.add(n);
        }
    }

    private void validateDirty() {
        if (dirty.isEmpty()) return;
        List<PlacedBlock> check = new ArrayList<>(dirty);
        dirty.clear();
        for (PlacedBlock b : check) {
            if (b.removed || !b.spawned || b.world() == null) continue;
            Block cell = b.block();
            boolean ok;
            if (b.type.baseBlock()) {
                ok = cell.getType() == Material.BARRIER;
            } else {
                ok = !cell.getType().isSolid() && !cell.isLiquid();
            }
            if (ok && b.type.needsSupport()) {
                Block support = b.mount == Mount.WALL ? cell.getRelative(b.facing.getOppositeFace()) : cell.getRelative(BlockFace.DOWN);
                ok = support.getType().isSolid();
            }
            if (!ok) {
                remove(b, true);
                continue;
            }
            // an entity that died without an event reaching us
            for (Entity e : b.entities) {
                if (!e.isValid()) {
                    despawn(b);
                    spawn(b);
                    break;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        touch(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().forEach(this::touch);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().forEach(this::touch);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        touch(e.getBlock().getRelative(e.getDirection()));
        for (Block moved : e.getBlocks()) {
            touch(moved);
            touch(moved.getRelative(e.getDirection()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block moved : e.getBlocks()) {
            touch(moved);
            touch(moved.getRelative(e.getDirection()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (at(e.getToBlock()) != null) dirty.add(at(e.getToBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        touch(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        touch(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        touch(e.getBlock());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        breaking.remove(e.getPlayer().getUniqueId());
        lastClick.remove(e.getPlayer().getUniqueId());
    }
}
