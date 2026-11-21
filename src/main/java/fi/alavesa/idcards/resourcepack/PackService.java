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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <p>The pack is built at start from the sources inside the jar, plus two generated files: the config include of the shader and, when
 * another text-shader plugin is installed, the merged core/text shader ({@link TextShaderMerge}). The result is
 * written to plugins/IdCards/idcards-pack.zip.
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

    /** How often our pack is sent again to stay above a pack that loaded after it, per player and session. */
    private static final int MAX_REPUSHES = 4;

    private final IdCardsPlugin plugin;
    private final Logger log;
    private final File pluginJar;
    private final Path dataFolder;
    private final Map<UUID, PlayerResourcePackStatusEvent.Status> status = new HashMap<>();
    private final Map<UUID, Integer> repushes = new HashMap<>();
    private final Set<UUID> sent = new HashSet<>();
    private final Map<UUID, Set<UUID>> pushedAfterOurs = new HashMap<>();
    private PackBuilder.@Nullable Built built;
    private @Nullable PackHttpServer http;
    private @Nullable SamePortHttp samePort;
    private String effective = "http", sourceSignature = "";
    private boolean merged;

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
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkSources, 100L, 100L);
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

    private List<Map.Entry<String, File>> candidates() {
        File plugins = dataFolder.getParent().toFile();
        return List.of(Map.entry("Radio", new File(plugins, cfg().packRadioZip)),
                Map.entry("SuperbVehicles", new File(plugins, cfg().packSuperbVehiclesZip)),
                Map.entry("CCTV", new File(plugins, cfg().packCctvZip)));
    }

    private String signature() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, File> c : candidates()) sb.append(c.getValue().length()).append(':').append(c.getValue().lastModified()).append(';');
        return sb.toString();
    }

    /** Another plugin rebuilt its pack (they do at start, some later): merge again and send the new pack out. */
    private void checkSources() {
        if (built == null || !cfg().packTextShader || signature().equals(sourceSignature)) return;
        log.info("another plugin's pack changed: merging the text shader again");
        reload();
    }

    private void rebuild() {
        Settings cfg = cfg();
        try {
            Map<String, byte[]> extra = new LinkedHashMap<>();
            String shader = "own (merging is off)";
            sourceSignature = signature();
            merged = false;
            if (cfg.packTextShader) {
                TextShaderMerge.Result result = TextShaderMerge.build(candidates(), log::warning);
                extra.putAll(result.files());
                merged = !result.files().isEmpty();
                shader = result.description();
            }
            // read by idcards_text_vertex.glsl / idcards_text_fragment.glsl
            extra.put("assets/idcards/shaders/include/idcards_config.glsl", String.format(Locale.ROOT,
                    "// Written by the IdCards plugin from config.yml (ui.*, inspect.*).%n"
                            + "const float IDC_UI_SCALE = %.3f;%nconst float IDC_INSPECT_SCALE = %.3f;%n"
                            + "const vec2 IDC_INSPECT_OFFSET = vec2(%d.0, %d.0);%nconst vec2 IDC_HINT_POS = vec2(%d.0, %d.0);%n"
                            + "const float IDC_HINT_SCALE = %.3f;%nconst float IDC_EFFECTS = %.1f;%n",
                    cfg.uiScale, cfg.inspectScale, cfg.inspectOffsetX, cfg.inspectOffsetY, cfg.uiHintX, cfg.uiHintY, cfg.uiHintScale,
                    cfg.uiEffects ? 1.0 : 0.0).getBytes(StandardCharsets.UTF_8));
            built = PackBuilder.fromJar(pluginJar, extra);
            Files.createDirectories(dataFolder);
            Files.write(dataFolder.resolve(FILE), built.zip());
            log.info("Resource pack: " + built.files() + " files, " + built.zip().length / 1024 + " KB, sha1 " + built.sha1() + ", text shader: " + shader);
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
        // after the other plugins' packs (they send at join): the pack pushed last is the one on top
        if (cfg().packSendDelayTicks == 0) send(p);
        else Bukkit.getScheduler().runTaskLater(plugin, () -> send(p), cfg().packSendDelayTicks);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        forget(e.getPlayer());
    }

    private void forget(Player p) {
        status.remove(p.getUniqueId());
        sent.remove(p.getUniqueId());
        repushes.remove(p.getUniqueId());
        pushedAfterOurs.remove(p.getUniqueId());
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
            return;
        }
        // Another pack was pushed after ours (ACCEPTED is the client's first answer to a push) and has now loaded: it
        // sits above ours, and if it carries a text shader without our hooks, the card HUD is gone. Send ours again.
        // Capped, so two plugins doing the same cannot ping-pong for ever.
        if (!sent.contains(p.getUniqueId()) || e.getID().equals(SCP079_PACK_ID) || e.getID().equals(INTERCOM_PACK_ID)) return;
        Set<UUID> newer = pushedAfterOurs.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        if (st == PlayerResourcePackStatusEvent.Status.ACCEPTED) {
            newer.add(e.getID());
        } else if (st == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED && newer.remove(e.getID())
                && repushes.merge(p.getUniqueId(), 1, Integer::sum) <= MAX_REPUSHES) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> send(p), 20L);
        }
    }

    public void send(Player p) {
        if (built == null || cfg().packDelivery.equals("none") || !p.isOnline()) return;
        URI uri = uri(p);
        if (uri == null) return;
        sent.add(p.getUniqueId());
        pushedAfterOurs.remove(p.getUniqueId());
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

    public boolean merged() {
        return merged;
    }
}
