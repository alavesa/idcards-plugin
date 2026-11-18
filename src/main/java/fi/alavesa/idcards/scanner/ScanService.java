package fi.alavesa.idcards.scanner;

import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.api.CardScanEvent;
import fi.alavesa.idcards.block.BlockType;
import fi.alavesa.idcards.block.Mount;
import fi.alavesa.idcards.block.PlacedBlock;
import fi.alavesa.idcards.card.CardData;
import fi.alavesa.idcards.util.Items;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Scanning: the decision, the swipe animation, the light and the sounds, and the report to the linked computers.
 *
 * <p>The decision is deliberately simple: a card that reads as VALID (bound to a UUID, signed by this server, not
 * replaced by a newer card) is granted, anything else is denied. There are no clearance levels here; a plugin that
 * wants them listens to {@link CardScanEvent} and flips the result.
 *
 * <p>Timeline of one scan (all lengths from config): light SCANNING and a card slides down through the slot for
 * swipe-ticks (one transformation change, interpolated by the client, so it is smooth at any TPS) -> light GRANTED
 * or DENIED with its sound for result-ticks -> light IDLE. The scanner ignores everything until cooldown-ticks
 * after that.
 */
public final class ScanService {

    private static final float CARD_SCALE = 0.42f;

    private final IdCardsPlugin plugin;
    /** Presenter -> the scanner their look last rested on (so one look gives one scan). */
    private final Map<UUID, Integer> lookingAt = new HashMap<>();

    public ScanService(IdCardsPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkPresenters, 5L, 4L);
    }

    /** Returns false when the scanner was busy or a listener cancelled the scan. */
    public boolean scan(Player holder, PlacedBlock scanner, ItemStack cardItem) {
        if (!(scanner.runtime instanceof ScannerType.Runtime rt) || !scanner.spawned()) return false;
        int now = Bukkit.getCurrentTick();
        if (now < rt.busyUntil) return false;
        CardData data = plugin.cards().read(cardItem);
        CardScanEvent.Reason reason = switch (data.status()) {
            case VALID -> CardScanEvent.Reason.VALID;
            case FORGED -> CardScanEvent.Reason.FORGED;
            case REVOKED -> CardScanEvent.Reason.REVOKED;
            default -> CardScanEvent.Reason.MALFORMED;
        };
        CardScanEvent event = new CardScanEvent(holder, scanner.id, scanner.label(), scanner.center(), data.owner(), reason);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        boolean granted = event.isGranted();

        var cfg = plugin.settings();
        int swipe = cfg.scannerSwipeTicks, result = cfg.scannerResultTicks, generation = rt.generation;
        rt.busyUntil = now + swipe + result + cfg.scannerCooldownTicks;
        light(scanner, rt, ScannerType.Light.SCANNING);
        Location at = scanner.center();
        at.getWorld().playSound(at, "idcards:swipe", cfg.scannerVolume, 1f);
        startSwipe(scanner, rt, swipe);
        ScanRecord record = new ScanRecord(System.currentTimeMillis(), scanner.id, scanner.label(), data.owner(), holder.getUniqueId(), granted,
                reason.name().toLowerCase(Locale.ROOT));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // the report does not depend on the animation: a scanner whose chunk unloaded mid-swipe still reports
            if (!scanner.removed()) plugin.computers().report(scanner, record);
            if (rt.generation != generation) return;
            endSwipe(rt);
            light(scanner, rt, granted ? ScannerType.Light.GRANTED : ScannerType.Light.DENIED);
            at.getWorld().playSound(at, granted ? "idcards:granted" : "idcards:denied", cfg.scannerVolume, 1f);
        }, swipe);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (rt.generation == generation) light(scanner, rt, ScannerType.Light.IDLE);
        }, swipe + result);
        return true;
    }

    private void light(PlacedBlock scanner, ScannerType.Runtime rt, ScannerType.Light light) {
        rt.light = light;
        if (rt.model.isValid()) rt.model.setItemStack(ScannerType.modelItem(scanner, light));
    }

    /**
     * A card prop above the slot, turned so its thin side runs through the slot and its long side stands up; two
     * ticks later (when clients have it) one transformation change moves it down, interpolated over the swipe.
     */
    private void startSwipe(PlacedBlock scanner, ScannerType.Runtime rt, int ticks) {
        double[] path = plugin.layout().numbers("scanner." + (scanner.mount == Mount.WALL ? "wall" : "floor") + ".swipe"); // x, y from, y to, z
        Quaternionf turn = new Quaternionf().rotateY((float) Math.PI / 2).rotateZ((float) Math.PI / 2);
        Vector3f scale = new Vector3f(CARD_SCALE);
        ItemDisplay card = plugin.blocks().spawnerFor(scanner).itemDisplay(scanner.local(path[0], path[1], path[3]), Items.model("card_prop"));
        card.setTransformation(new Transformation(new Vector3f(), turn, scale, new Quaternionf()));
        card.setBrightness(new Display.Brightness(15, 15));
        rt.card = card;
        float drop = (float) ((path[2] - path[1]) / 16.0);
        int generation = rt.generation;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (rt.generation != generation || !card.isValid()) return;
            card.setInterpolationDelay(0);
            card.setInterpolationDuration(Math.max(1, ticks - 3));
            card.setTransformation(new Transformation(new Vector3f(0f, drop, 0f), turn, scale, new Quaternionf()));
        }, 2L);
    }

    private void endSwipe(ScannerType.Runtime rt) {
        if (rt.card != null) plugin.blocks().discard(rt.card);
        rt.card = null;
    }

    /**
     * The second way to scan: holding the card out (F) while standing close to a scanner and looking at it. One
     * scan per look: the look has to leave the scanner before it can scan the same card again.
     */
    private void checkPresenters() {
        Map<UUID, UUID> presenters = plugin.presenting().owners();
        lookingAt.keySet().retainAll(presenters.keySet());
        if (presenters.isEmpty()) return;
        double range = plugin.settings().scannerPresentRange;
        BlockType type = plugin.blocks().type("scanner");
        for (UUID id : presenters.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            Location eye = p.getEyeLocation();
            Vector dir = eye.getDirection();
            PlacedBlock target = null;
            double best = Double.MAX_VALUE;
            for (PlacedBlock scanner : plugin.blocks().near(p.getLocation(), range + 1, type)) {
                BoundingBox box = plugin.blocks().hitbox(scanner);
                if (box == null) continue;
                RayTraceResult hit = box.rayTrace(eye.toVector(), dir, range);
                if (hit == null) continue;
                double d = hit.getHitPosition().distance(eye.toVector());
                if (d < best) {
                    best = d;
                    target = scanner;
                }
            }
            if (target != null && best > 0.05 && p.getWorld().rayTraceBlocks(eye, dir, best, FluidCollisionMode.NEVER, true) != null) target = null;
            if (target == null) {
                lookingAt.remove(id);
                continue;
            }
            Integer before = lookingAt.get(id);
            if (before != null && before == target.id) continue;
            // remembered only once the scan really started: a busy scanner is tried again on the next round
            if (scan(p, target, p.getInventory().getItemInMainHand())) lookingAt.put(id, target.id);
        }
    }
}
