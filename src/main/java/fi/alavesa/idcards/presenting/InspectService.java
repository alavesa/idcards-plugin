package fi.alavesa.idcards.presenting;

import fi.alavesa.idcards.IdCardsPlugin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * The viewer's side of presenting: whoever looks at a presenting player from close by gets that card on their HUD
 * (the same renderer as the full-screen card, in its side layout) for as long as they keep looking. It is only a
 * boss bar: the viewer is not frozen and nothing else about them changes.
 *
 * <p>Cost: every {@code inspect.interval-ticks}, one nearby-players query per PRESENTER (not per online player) and
 * one box ray test per player found; a block ray trace only for the players whose look ray hits the presenter.
 */
public final class InspectService {

    private record Shown(BossBar bar, UUID presenter, UUID owner, boolean plain) {
    }

    private final IdCardsPlugin plugin;
    private final Map<UUID, Shown> shown = new HashMap<>();

    InspectService(IdCardsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Works out who looks at whom. {@code presenters}: presenter -> the owner of the card they hold out. */
    void update(Map<UUID, UUID> presenters) {
        Map<UUID, UUID[]> targets = new HashMap<>(); // viewer -> {presenter, owner}
        Map<UUID, Double> best = new HashMap<>();
        double range = plugin.settings().inspectRange;
        if (plugin.settings().inspectEnabled) {
            for (Map.Entry<UUID, UUID> e : presenters.entrySet()) {
                Player presenter = Bukkit.getPlayer(e.getKey());
                if (presenter == null) continue;
                BoundingBox box = presenter.getBoundingBox().expand(0.35);
                for (Player viewer : presenter.getWorld().getNearbyPlayers(presenter.getLocation(), range + 1)) {
                    if (viewer.equals(presenter) || !viewer.canSee(presenter) || plugin.ui().isOpen(viewer)) continue;
                    Location eye = viewer.getEyeLocation();
                    Vector dir = eye.getDirection();
                    RayTraceResult hit = box.rayTrace(eye.toVector(), dir, range);
                    if (hit == null) continue;
                    double distance = hit.getHitPosition().distance(eye.toVector());
                    Double other = best.get(viewer.getUniqueId());
                    if (other != null && other <= distance) continue;
                    if (distance > 0.05 && viewer.getWorld().rayTraceBlocks(eye, dir, distance, FluidCollisionMode.NEVER, true) != null) continue; // a wall between
                    best.put(viewer.getUniqueId(), distance);
                    targets.put(viewer.getUniqueId(), new UUID[]{e.getKey(), e.getValue()});
                }
            }
        }
        for (UUID viewerId : List.copyOf(shown.keySet())) {
            UUID[] t = targets.get(viewerId);
            Shown s = shown.get(viewerId);
            if (t == null || !t[0].equals(s.presenter) || !t[1].equals(s.owner)) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) viewer.hideBossBar(s.bar);
                shown.remove(viewerId);
            }
        }
        for (Map.Entry<UUID, UUID[]> e : targets.entrySet()) {
            if (shown.containsKey(e.getKey())) continue;
            Player viewer = Bukkit.getPlayer(e.getKey());
            if (viewer == null) continue;
            BossBar bar = BossBar.bossBar(plugin.renderer().render(plugin.views().of(e.getValue()[1]), true, plugin.ui().plain(viewer)), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            viewer.showBossBar(bar);
            shown.put(e.getKey(), new Shown(bar, e.getValue()[0], e.getValue()[1], plugin.ui().plain(viewer)));
        }
    }

    public void hide(Player viewer) {
        Shown s = shown.remove(viewer.getUniqueId());
        if (s != null) viewer.hideBossBar(s.bar);
    }

    /** A name, skin or prefix changed. */
    public void refresh(UUID owner) {
        for (Shown s : shown.values()) {
            if (s.owner.equals(owner)) s.bar.name(plugin.renderer().render(plugin.views().of(owner), true, s.plain));
        }
    }

    void hideAll() {
        for (Map.Entry<UUID, Shown> e : shown.entrySet()) {
            Player viewer = Bukkit.getPlayer(e.getKey());
            if (viewer != null) viewer.hideBossBar(e.getValue().bar);
        }
        shown.clear();
    }
}
