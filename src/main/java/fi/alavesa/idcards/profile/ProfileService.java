package fi.alavesa.idcards.profile;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import fi.alavesa.idcards.storage.Database;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Who a UUID is: current name and skin, for players who are online, offline, or have never joined.
 *
 * <p>A card stores only a UUID. Name and skin are looked up here every time a card is shown, so a name change shows
 * on every card of that player. Sources, in order: the online player; the cache (memory, then the profiles table,
 * filled whenever a player joins); the server's own profile lookup, which asks Mojang (in the background). Lookups
 * never block: {@link #get} answers with what is known right now and, when something better arrives, the listeners
 * run on the main thread so open card views can redraw.
 *
 * <p>The skin travels to clients as the signed "textures" property inside the player-head text component, so a
 * client can draw the face of somebody who is offline without asking anyone.
 */
public final class ProfileService implements Listener {

    /** Name and textures may be null when nothing is known (yet). */
    public record Profile(UUID id, @Nullable String name, @Nullable String texValue, @Nullable String texSignature) {
    }

    private static final Pattern SKIN_URL = Pattern.compile("\"SKIN\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"(https?://textures\\.minecraft\\.net/[^\"]+)\"");

    private final Plugin plugin;
    private final Database db;
    private final Map<UUID, Profile> cache = new ConcurrentHashMap<>();
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    /** 8x8 ARGB faces (hat layer already painted over the face) by skin URL; an empty array = download failed. */
    private final Map<String, int[]> faces = new ConcurrentHashMap<>();
    private final Set<String> facesLoading = ConcurrentHashMap.newKeySet();
    private final List<Consumer<UUID>> listeners = new ArrayList<>();

    public ProfileService(Plugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        for (Player p : Bukkit.getOnlinePlayers()) capture(p);
    }

    /** Called on the main thread when a name, skin or face became known or changed. */
    public void onUpdate(Consumer<UUID> listener) {
        listeners.add(listener);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        capture(e.getPlayer());
    }

    private void capture(Player p) {
        String value = null, signature = null;
        for (ProfileProperty prop : p.getPlayerProfile().getProperties()) {
            if (prop.getName().equals("textures")) {
                value = prop.getValue();
                signature = prop.getSignature();
            }
        }
        store(new Profile(p.getUniqueId(), p.getName(), value, signature));
    }

    private void store(Profile profile) {
        Profile before = cache.put(profile.id(), profile);
        if (profile.equals(before)) return;
        db.write("INSERT INTO profiles (uuid, name, tex_value, tex_signature, updated_at) VALUES (?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET "
                        + "name = excluded.name, tex_value = excluded.tex_value, tex_signature = excluded.tex_signature, updated_at = excluded.updated_at",
                profile.id().toString(), profile.name(), profile.texValue(), profile.texSignature(), System.currentTimeMillis());
    }

    /** What is known right now. Never blocks; starts a background lookup when the cache has nothing. */
    public Profile get(UUID id) {
        Profile known = cache.get(id);
        if (known != null) return known;
        if (loading.add(id)) lookup(id);
        return new Profile(id, null, null, null);
    }

    /** The current name, or null when nobody by that UUID is known yet. Main thread. */
    public @Nullable String name(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) return online.getName();
        String cached = get(id).name();
        if (cached != null) return cached;
        OfflinePlayer offline = Bukkit.getOfflinePlayer(id); // the server's own user cache; no network
        return offline.getName();
    }

    private void lookup(UUID id) {
        db.query("SELECT name, tex_value, tex_signature FROM profiles WHERE uuid = ?",
                rs -> new Profile(id, rs.getString(1), rs.getString(2), rs.getString(3)), id.toString()).whenComplete((rows, error) -> {
            if (error == null && !rows.isEmpty()) {
                cache.putIfAbsent(id, rows.getFirst());
                loading.remove(id);
                notifyListeners(id);
                return;
            }
            // never seen on this server: ask the session server (this thread may block; it is not the main thread)
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    PlayerProfile profile = Bukkit.createProfile(id);
                    profile.complete(true);
                    String value = null, signature = null;
                    for (ProfileProperty prop : profile.getProperties()) {
                        if (prop.getName().equals("textures")) {
                            value = prop.getValue();
                            signature = prop.getSignature();
                        }
                    }
                    if (profile.getName() != null || value != null) store(new Profile(id, profile.getName(), value, signature));
                    else cache.putIfAbsent(id, new Profile(id, null, null, null)); // unknown: do not ask again this run
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.FINE, "Profile lookup failed for " + id, e);
                } finally {
                    loading.remove(id);
                    notifyListeners(id);
                }
            });
        });
    }

    private void notifyListeners(UUID id) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> listeners.forEach(l -> l.accept(id)));
    }

    /**
     * The player-head text object for this UUID. With cached textures the client needs no lookup (works for offline
     * owners); without them it gets the UUID alone and resolves the skin itself, showing the default skin meanwhile.
     */
    public PlayerHeadObjectContents head(UUID id) {
        Profile p = get(id);
        PlayerHeadObjectContents.Builder b = ObjectContents.playerHead().hat(true);
        if (p.texValue() != null) {
            b.id(id);
            if (p.name() != null && PlayerHeadObjectContents.isValidName(p.name())) b.name(p.name());
            b.profileProperty(p.texSignature() == null ? PlayerHeadObjectContents.property("textures", p.texValue())
                    : PlayerHeadObjectContents.property("textures", p.texValue(), p.texSignature()));
        } else {
            b.id(id);
        }
        return b.build();
    }

    /**
     * The 8x8 face as ARGB (for the hand-held map picture), or null while it is not known; then the download starts
     * and the listeners hear about it.
     */
    public int @Nullable [] face(UUID id) {
        Profile p = get(id);
        if (p.texValue() == null) return null;
        String url = skinUrl(p.texValue());
        if (url == null) return null;
        int[] face = faces.get(url);
        if (face != null) return face.length == 0 ? null : face;
        if (facesLoading.add(url) && plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                int[] result = new int[0];
                try {
                    BufferedImage skin = ImageIO.read(URI.create(url).toURL());
                    if (skin != null && skin.getWidth() == 64) {
                        result = new int[64];
                        for (int y = 0; y < 8; y++) {
                            for (int x = 0; x < 8; x++) {
                                int base = skin.getRGB(8 + x, 8 + y) | 0xFF000000, hat = skin.getRGB(40 + x, 8 + y);
                                result[y * 8 + x] = (hat >>> 24) > 127 ? hat | 0xFF000000 : base;
                            }
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.FINE, "Skin download failed: " + url, e);
                }
                faces.put(url, result);
                facesLoading.remove(url);
                if (result.length > 0) notifyListeners(id);
            });
        }
        return null;
    }

    /** Only textures.minecraft.net addresses are ever fetched, whatever a profile claims. */
    private static @Nullable String skinUrl(String texturesValue) {
        try {
            String json = new String(Base64.getDecoder().decode(texturesValue), StandardCharsets.UTF_8);
            Matcher m = SKIN_URL.matcher(json);
            return m.find() ? m.group(1) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
