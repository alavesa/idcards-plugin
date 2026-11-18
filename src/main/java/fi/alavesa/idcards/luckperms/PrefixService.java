package fi.alavesa.idcards.luckperms;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Cached prefixes. {@link #get} never blocks: it answers from the cache (or with the fallback) and, when the cache
 * has nothing or the entry is old, asks the source in the background and tells the listeners on the main thread when
 * the answer differs. LuckPerms' own change events refresh entries at once.
 */
public final class PrefixService {

    private record Entry(String raw, long loadedAt) {
    }

    private final Plugin plugin;
    private final @Nullable PrefixSource source;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> loading = new ConcurrentHashMap<>();
    private final List<Consumer<UUID>> listeners = new ArrayList<>();
    private volatile String fallback;
    private volatile long maxAgeMillis;

    public PrefixService(Plugin plugin, String fallback, int refreshSeconds) {
        this.plugin = plugin;
        configure(fallback, refreshSeconds);
        PrefixSource found = null;
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            try {
                found = new LuckPermsPrefixes(plugin);
                found.onChange(this::refresh);
                plugin.getLogger().info("LuckPerms found: cards show its prefixes");
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "LuckPerms is installed but its API could not be used; cards show the fallback prefix", t);
                found = null;
            }
        } else {
            plugin.getLogger().info("LuckPerms not found: cards show the fallback prefix from config.yml");
        }
        source = found;
    }

    public void configure(String fallback, int refreshSeconds) {
        this.fallback = fallback;
        this.maxAgeMillis = Math.max(5, refreshSeconds) * 1000L;
    }

    public boolean luckPerms() {
        return source != null;
    }

    /** Called on the main thread with the player whose prefix changed. */
    public void onChange(Consumer<UUID> listener) {
        listeners.add(listener);
    }

    /** The raw prefix as far as it is known now; the fallback when the player has none or it is not loaded yet. */
    public String get(UUID player) {
        Entry e = cache.get(player);
        if (e == null || System.currentTimeMillis() - e.loadedAt > maxAgeMillis) refresh(player);
        return e == null || e.raw.isBlank() ? fallback : e.raw;
    }

    /** Asks the source again (any thread). */
    public void refresh(UUID player) {
        if (source == null || loading.putIfAbsent(player, Boolean.TRUE) != null) return;
        source.load(player).whenComplete((raw, error) -> {
            loading.remove(player);
            if (error != null) {
                plugin.getLogger().log(Level.FINE, "Prefix of " + player + " could not be loaded", error);
                return;
            }
            String value = raw == null ? "" : raw;
            Entry before = cache.put(player, new Entry(value, System.currentTimeMillis()));
            if ((before == null || !before.raw.equals(value)) && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> listeners.forEach(l -> l.accept(player)));
            }
        });
    }

    public void forget(UUID player) {
        cache.remove(player);
    }

    public void close() {
        if (source != null) source.close();
    }
}
