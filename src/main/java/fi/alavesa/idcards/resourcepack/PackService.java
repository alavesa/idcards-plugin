package fi.alavesa.idcards.resourcepack;

import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.config.Settings;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.jspecify.annotations.Nullable;

/**
 * Builds, hosts and sends the pack as an ADDITIONAL server resource pack (replace = false), so the server's own pack
 * and other plugins' packs keep working. Whether a client loaded it is logged but never changes what the plugin does.
 *
 * <p>The pack only adds files in its own namespace (models, textures, a font, sounds): it overrides no vanilla file
 * and ships no shader, so it does not matter where among the server's packs it ends up, and it never has to be sent
 * twice. It is built at start from the sources inside the jar and written to plugins/IdCards/idcards-pack.zip.
 *
 * <p>pack.delivery:
 * <ul>
 *   <li>auto (default): same-port, or http when the Minecraft port cannot be hooked</li>
 *   <li>same-port: served on the Minecraft port itself (nothing to open in a firewall; not behind a proxy)</li>
 *   <li>http: served by the built-in HTTP server on pack.http.port (that port must be reachable by players)</li>
 *   <li>url: you upload plugins/IdCards/idcards-pack.zip yourself; pack.url points at it</li>
 *   <li>none: you merged the pack into your server pack; nothing is sent</li>
 * </ul>
 */
public final class PackService implements Listener {

    /** Fixed id: clients and the status event identify our pack by it across restarts. */
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("idcards:pack".getBytes(StandardCharsets.UTF_8));
    public static final String FILE = "idcards-pack.zip";

    /**
     * The SCP079 pack. Its text shader is built on top of ours (it reads idcards-pack.zip and adds its own hooks), so
     * it belongs above the IdCards pack: sending ours again after it would only start a tug of war over the top place.
     */
    private static final UUID SCP079_PACK_ID = UUID.nameUUIDFromBytes("scp079:pack".getBytes(StandardCharsets.UTF_8));
    /** The Intercom pack: its text shader is built on top of every other plugin's (ours included), so it belongs above. */
    private static final UUID INTERCOM_PACK_ID = UUID.nameUUIDFromBytes("intercom:pack".getBytes(StandardCharsets.UTF_8));

    private final IdCardsPlugin plugin;
    private final Logger log;
    private final File pluginJar;
    private final Path dataFolder;
    private final Map<UUID, PlayerResourcePackStatusEvent.Status> status = new HashMap<>();
    private PackBuilder.@Nullable Built built;
    private @Nullable PackHttpServer http;
    private @Nullable SamePortHttp samePort;
    private String effective = "http";

    public PackService(IdCardsPlugin plugin, File pluginJar) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.pluginJar = pluginJar;
        this.dataFolder = plugin.getDataFolder().toPath();
    }

    private Settings cfg() {
        return plugin.settings();
    }

    public void start() {
        rebuild();
    }

    public void stop() {
        if (http != null) http.stop();
        http = null;
        if (samePort != null) samePort.uninstall();
        samePort = null;
    }

    /** After /idcard reload: build again and, when the bytes changed, send the new pack to everyone. */
    public void reload() {
        stop();
        String before = built == null ? "" : built.sha1();
        rebuild();
        if (built != null && !built.sha1().equals(before)) {
            for (Player p : Bukkit.getOnlinePlayers()) send(p);
        }
    }

    public String delivery() {
        return cfg().packDelivery.equals("auto") ? effective : cfg().packDelivery;
    }

    public @Nullable String sha1() {
        return built == null ? null : built.sha1();
    }

    private void rebuild() {
        Settings cfg = cfg();
        try {
            built = PackBuilder.fromJar(pluginJar, Map.of());
            Files.createDirectories(dataFolder);
            Files.write(dataFolder.resolve(FILE), built.zip());
            log.info("Resource pack: " + built.files() + " files, " + built.zip().length / 1024 + " KB, sha1 " + built.sha1());
        } catch (IOException e) {
            log.log(Level.SEVERE, "Could not build the resource pack", e);
            built = null;
            return;
        }
        if (cfg.packDelivery.equals("same-port") || cfg.packDelivery.equals("auto")) {
            SamePortHttp h = new SamePortHttp(Bukkit.getServer(), "idcards", path -> dataFolder.resolve(FILE).toFile());
            String problem = h.install();
            if (problem == null) {
                samePort = h;
                log.info("Resource pack served on the Minecraft port (" + Bukkit.getPort() + "): nothing to open in a firewall");
            } else {
                log.warning("Could not serve the pack on the Minecraft port (" + problem + ")"
                        + (cfg.packDelivery.equals("auto") ? "; using the HTTP server on port " + cfg.packHttpPort : ""));
            }
            effective = samePort != null ? "same-port" : "http";
        }
        if (delivery().equals("http")) {
            try {
                http = new PackHttpServer(cfg.packHttpBind, cfg.packHttpPort);
                http.serve(built.zip(), built.sha1());
            } catch (IOException e) {
                log.log(Level.SEVERE, "Could not start the pack HTTP server on " + cfg.packHttpBind + ":" + cfg.packHttpPort
                        + " (port in use? set pack.http.port or pack.delivery: url)", e);
            }
        }
        if (cfg.packDelivery.equals("url")) {
            if (cfg.packUrl.isBlank()) log.warning("pack.delivery is url but pack.url is empty: the pack is not sent");
            else if (!cfg.packSha1.isEmpty() && !cfg.packSha1.equals(built.sha1())) {
                log.warning("pack.sha1 in config.yml differs from the pack this plugin built (" + built.sha1() + "). Upload plugins/IdCards/"
                        + FILE + " again, or the hosted pack and the plugin disagree about the layout.");
            }
        }
    }

    // ------------------------------------------------------------------ sending

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        forget(p);
        if (cfg().packSendDelayTicks == 0) send(p);
        else Bukkit.getScheduler().runTaskLater(plugin, () -> send(p), cfg().packSendDelayTicks);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        forget(e.getPlayer());
    }

    private void forget(Player p) {
        status.remove(p.getUniqueId());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        Player p = e.getPlayer();
        PlayerResourcePackStatusEvent.Status st = e.getStatus();
        if (e.getID().equals(PACK_ID)) {
            // DISCARDED is about the copy an earlier push left behind (we push again to stay on top)
            if (st == PlayerResourcePackStatusEvent.Status.DISCARDED) return;
            status.put(p.getUniqueId(), st);
            // Nothing in the plugin depends on this answer: a server that merged the pack into its own, or a player
            // who has it installed by hand, must work the same. The console line is for the admin only.
            if (st == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD || st == PlayerResourcePackStatusEvent.Status.FAILED_RELOAD
                    || st == PlayerResourcePackStatusEvent.Status.INVALID_URL) {
                log.warning(p.getName() + " could not load the resource pack (" + st + ") from " + uri(p)
                        + ". If the address answers 404, another plugin is answering downloads on the Minecraft port; behind a proxy set pack.delivery: http or url.");
            }
        }
    }

    public void send(Player p) {
        if (built == null || cfg().packDelivery.equals("none") || !p.isOnline()) return;
        URI uri = uri(p);
        if (uri == null) return;
        ResourcePackRequest.Builder req = ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(PACK_ID, uri, hash()))
                .replace(false)
                .required(cfg().packRequired);
        if (!cfg().packPrompt.isBlank()) req.prompt(MiniMessage.miniMessage().deserialize(cfg().packPrompt));
        p.sendResourcePacks(req.build());
    }

    /** The SHA-1 the client is told: for a self-hosted zip the configured one, when set. */
    private String hash() {
        return cfg().packDelivery.equals("url") && !cfg().packSha1.isEmpty() ? cfg().packSha1 : built.sha1();
    }

    private @Nullable URI uri(Player p) {
        Settings cfg = cfg();
        if (cfg.packDelivery.equals("url")) return cfg.packUrl.isBlank() ? null : URI.create(cfg.packUrl);
        String host = cfg.packPublicHost;
        InetSocketAddress vh = p.getConnection().getVirtualHost();
        if (host.isBlank()) {
            // the host name the player typed to connect is the one that reaches this machine
            host = vh == null ? "" : vh.getHostString();
            int nul = host.indexOf('\0');
            if (nul >= 0) host = host.substring(0, nul);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        }
        if (host.isBlank()) host = Bukkit.getIp().isBlank() ? "localhost" : Bukkit.getIp();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]"; // IPv6 literal
        int port = cfg.packHttpPort;
        if (delivery().equals("same-port")) {
            port = Bukkit.getPort();
            if (cfg.packPublicHost.isBlank() && vh != null && vh.getPort() > 0) port = vh.getPort();
        }
        return URI.create("http://" + host + ":" + port + "/idcards/" + built.sha1() + ".zip");
    }

    // ------------------------------------------------------------------ status

    public PlayerResourcePackStatusEvent.@Nullable Status status(Player p) {
        return status.get(p.getUniqueId());
    }
}
