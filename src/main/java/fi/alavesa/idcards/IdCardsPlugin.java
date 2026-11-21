package fi.alavesa.idcards;

import fi.alavesa.idcards.block.BlockManager;
import fi.alavesa.idcards.card.CardService;
import fi.alavesa.idcards.card.ItemGuard;
import fi.alavesa.idcards.command.IdCardCommand;
import fi.alavesa.idcards.computer.ComputerService;
import fi.alavesa.idcards.computer.ComputerType;
import fi.alavesa.idcards.config.Lang;
import fi.alavesa.idcards.config.Settings;
import fi.alavesa.idcards.link.LinkService;
import fi.alavesa.idcards.luckperms.PrefixService;
import fi.alavesa.idcards.nms.RideBridge;
import fi.alavesa.idcards.presenting.PresentService;
import fi.alavesa.idcards.profile.ProfileService;
import fi.alavesa.idcards.resourcepack.PackService;
import fi.alavesa.idcards.scanner.ScanService;
import fi.alavesa.idcards.scanner.ScannerType;
import fi.alavesa.idcards.storage.Database;
import fi.alavesa.idcards.ui.CardViews;
import fi.alavesa.idcards.ui.Layout;
import fi.alavesa.idcards.util.Keys;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Team;

/**
 * ID cards for RP servers: a card bound to a player that shows its owner's details when held and held out, card
 * scanners and scanner computers. This class only wires the parts together; see the package of each part.
 */
public final class IdCardsPlugin extends JavaPlugin implements Listener {

    /** The scoreboard team of IdCards 0.x that hides name tags; other plugins mirror it by this name. */
    public static final String NAMETAG_TEAM = "idc_hidden";

    private Settings settings;
    private Lang lang;
    private Keys keys;
    private Layout layout;
    private Database db;
    private ProfileService profiles;
    private PrefixService prefixes;
    private CardService cards;
    private CardViews views;
    private BlockManager blocks;
    private LinkService links;
    private ComputerService computers;
    private ScanService scans;
    private RideBridge rides;
    private PresentService presenting;
    private PackService pack;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig());
        lang = new Lang(this, settings.language);
        keys = new Keys(this);
        try {
            layout = new Layout(this);
            db = new Database(getLogger());
            db.open(new File(getDataFolder(), "idcards.db"));
            profiles = new ProfileService(this, db);
            prefixes = new PrefixService(this, settings.fallbackPrefix, settings.prefixRefreshSeconds);
            cards = new CardService(this);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "IdCards could not start", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        views = new CardViews(this);
        rides = new RideBridge(getLogger());
        blocks = new BlockManager(this);
        blocks.register(new ScannerType(this));
        blocks.register(new ComputerType(this));
        links = new LinkService(this);
        computers = new ComputerService(this);
        scans = new ScanService(this);
        presenting = new PresentService(this);
        pack = new PackService(this, getFile());

        var pm = Bukkit.getPluginManager();
        for (Listener l : new Listener[]{this, profiles, new ItemGuard(this), blocks, links, computers, presenting, pack}) pm.registerEvents(l, this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(new IdCardCommand(this).build(), "IdCards: cards, scanners, computers"));

        // a name, skin or prefix changed: redraw whatever shows that player
        profiles.onUpdate(this::refresh);
        prefixes.onChange(this::refresh);

        presenting.sweep();
        blocks.load();
        links.load();
        computers.refreshAll();
        presenting.start();
        pack.start();
        applyNametags();
        for (Player p : Bukkit.getOnlinePlayers()) pack.send(p); // loaded while the server runs
    }

    @Override
    public void onDisable() {
        if (presenting != null) presenting.stopAll();
        if (blocks != null) blocks.shutdown();
        if (pack != null) pack.stop();
        if (prefixes != null) prefixes.close();
        if (db != null) db.close();
    }

    /** /idcard reload. Held cards and placed blocks carry on with the new settings. */
    public void reload() {
        reloadConfig();
        settings = new Settings(getConfig());
        lang = new Lang(this, settings.language);
        prefixes.configure(settings.fallbackPrefix, settings.prefixRefreshSeconds);
        pack.reload();
        presenting.stopAll(); // held cards are made again on the next tick, with the new texts
        computers.refreshAll();
        applyNametags();
    }

    private void refresh(UUID player) {
        presenting.refresh(player);
    }

    /** nametags.hide: the same team IdCards 0.x used, so plugins that mirror it keep working. */
    private void applyNametags() {
        var board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(NAMETAG_TEAM);
        if (!settings.hideNametags) return; // never unregister here: an admin may run the team by hand
        if (team == null) team = board.registerNewTeam(NAMETAG_TEAM);
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        for (Player online : Bukkit.getOnlinePlayers()) team.addEntry(online.getName());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!settings.hideNametags) return;
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(NAMETAG_TEAM);
        if (team != null) team.addEntry(e.getPlayer().getName());
    }

    public Settings settings() { return settings; }
    public Lang lang() { return lang; }
    public Keys keys() { return keys; }
    public Layout layout() { return layout; }
    public Database db() { return db; }
    public ProfileService profiles() { return profiles; }
    public PrefixService prefixes() { return prefixes; }
    public CardService cards() { return cards; }
    public CardViews views() { return views; }
    public BlockManager blocks() { return blocks; }
    public LinkService links() { return links; }
    public ComputerService computers() { return computers; }
    public ScanService scans() { return scans; }
    public RideBridge rides() { return rides; }
    public PresentService presenting() { return presenting; }
    public PackService pack() { return pack; }
}
