package fi.alavesa.idcards.computer;

import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.block.PlacedBlock;
import fi.alavesa.idcards.config.Lang;
import fi.alavesa.idcards.scanner.ScanRecord;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * What computers know: the scans their linked scanners sent (kept in the scans table, the newest
 * {@code computer.log.size} per computer), what is on each screen, and the log dialog.
 */
public final class ComputerService implements Listener {

    private static final String NAMESPACE = "idcards";

    private final IdCardsPlugin plugin;
    private final Map<Integer, Deque<ScanRecord>> logs = new HashMap<>();
    /** The scan a screen shows right now and the time it runs out, so a screen that respawns can show it again. */
    private final Map<Integer, ScanRecord> showing = new HashMap<>();

    public ComputerService(IdCardsPlugin plugin) {
        this.plugin = plugin;
        int size = plugin.settings().computerLogSize;
        record Row(int computer, ScanRecord scan) {
        }
        for (Row row : plugin.db().load("SELECT computer, at, scanner, scanner_name, owner, holder, granted, reason FROM scans ORDER BY id",
                rs -> new Row(rs.getInt(1), new ScanRecord(rs.getLong(2), rs.getInt(3), rs.getString(4), uuid(rs.getString(5)), uuid(rs.getString(6)),
                        rs.getInt(7) != 0, rs.getString(8))))) {
            Deque<ScanRecord> log = logs.computeIfAbsent(row.computer, k -> new ArrayDeque<>());
            log.addLast(row.scan);
            while (log.size() > size) log.removeFirst();
        }
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String formatTime(IdCardsPlugin plugin, long at) {
        return Screen.formatTime(plugin.settings().timeFormat, plugin.settings().timeZone, at);
    }

    /** A scanner finished a scan: every linked computer logs it and shows it. */
    public void report(PlacedBlock scanner, ScanRecord record) {
        int size = plugin.settings().computerLogSize;
        for (PlacedBlock computer : plugin.links().computersOf(scanner)) {
            Deque<ScanRecord> log = logs.computeIfAbsent(computer.id, k -> new ArrayDeque<>());
            log.addLast(record);
            while (log.size() > size) log.removeFirst();
            plugin.db().write("INSERT INTO scans (computer, scanner, scanner_name, owner, holder, at, granted, reason) VALUES (?,?,?,?,?,?,?,?)",
                    computer.id, record.scannerId(), record.scannerName(), record.owner() == null ? null : record.owner().toString(),
                    record.holder() == null ? null : record.holder().toString(), record.at(), record.granted() ? 1 : 0, record.reason());
            plugin.db().write("DELETE FROM scans WHERE computer = ? AND id NOT IN (SELECT id FROM scans WHERE computer = ? ORDER BY id DESC LIMIT ?)",
                    computer.id, computer.id, size);
            showing.put(computer.id, record);
            if (computer.runtime instanceof Screen screen) screen.show(record, () -> idle(computer));
        }
    }

    private void idle(PlacedBlock computer) {
        showing.remove(computer.id);
        if (computer.runtime instanceof Screen screen && computer.spawned()) screen.idle(plugin.links().scannersOf(computer).size());
    }

    /** A screen was (re)created: its chunk loaded, or it was healed. */
    void restore(PlacedBlock computer) {
        if (!(computer.runtime instanceof Screen screen)) return;
        ScanRecord record = showing.get(computer.id);
        long timeout = plugin.settings().computerScreenTimeoutSeconds * 1000L;
        if (record != null && (timeout <= 0 || System.currentTimeMillis() - record.at() < timeout)) screen.show(record, () -> idle(computer));
        else idle(computer);
    }

    /** Links changed, a name or prefix changed, the config was reloaded: draw every loaded screen again. */
    public void refreshAll() {
        var type = plugin.blocks().type("computer");
        for (PlacedBlock b : plugin.blocks().all()) {
            if (b.type == type && b.spawned()) restore(b);
        }
    }

    void forget(PlacedBlock computer) {
        logs.remove(computer.id);
        showing.remove(computer.id);
        plugin.db().write("DELETE FROM scans WHERE computer = ?", computer.id);
    }

    // ------------------------------------------------------------------ the log dialog

    public void openLog(Player p, PlacedBlock computer, int page) {
        Lang lang = plugin.lang();
        if (!p.hasPermission("idcard.computer.log")) {
            lang.send(p, "computer.no-permission");
            return;
        }
        List<ScanRecord> newestFirst = new ArrayList<>(logs.getOrDefault(computer.id, new ArrayDeque<>())).reversed();
        int perPage = plugin.settings().computerLogPageSize, pages = Math.max(1, (newestFirst.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(lang.get("computer.log.header", Lang.ph("computer", computer.label()),
                Lang.ph("scanners", plugin.links().scannersOf(computer).size()), Lang.ph("page", page + 1), Lang.ph("pages", pages)), 300));
        if (newestFirst.isEmpty()) body.add(DialogBody.plainMessage(lang.get("computer.log.empty"), 300));
        for (ScanRecord r : newestFirst.subList(Math.min(newestFirst.size(), page * perPage), Math.min(newestFirst.size(), (page + 1) * perPage))) {
            String name = r.owner() == null ? null : plugin.profiles().name(r.owner());
            Component who = r.owner() == null ? lang.get("computer.log.unreadable")
                    : Component.text(name == null ? r.owner().toString().substring(0, 8) : name);
            body.add(DialogBody.plainMessage(lang.get(r.granted() ? "computer.log.entry-granted" : "computer.log.entry-denied",
                    Lang.ph("time", formatTime(plugin, r.at())), Lang.ph("player", who), Lang.ph("scanner", r.scannerName()),
                    Lang.ph("reason", lang.raw("computer.log.reason." + r.reason()))), 300));
        }
        DialogBase base = DialogBase.builder(lang.get("computer.log.title")).canCloseWithEscape(true).pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE).body(body).build();
        List<ActionButton> buttons = new ArrayList<>();
        if (page > 0) buttons.add(button(lang.get("computer.log.newer"), "log/" + computer.id + "/" + (page - 1)));
        if (page < pages - 1) buttons.add(button(lang.get("computer.log.older"), "log/" + computer.id + "/" + (page + 1)));
        ActionButton close = ActionButton.builder(lang.get("computer.log.close")).width(120).build();
        Dialog dialog = buttons.isEmpty() ? Dialog.create(b -> b.empty().base(base).type(DialogType.notice(close)))
                : Dialog.create(b -> b.empty().base(base).type(DialogType.multiAction(buttons, close, 2)));
        p.showDialog(dialog);
    }

    private static ActionButton button(Component label, String path) {
        return ActionButton.builder(label).width(120).action(DialogAction.customClick(Key.key(NAMESPACE, path), null)).build();
    }

    @EventHandler
    public void onClick(PlayerCustomClickEvent e) {
        Key id = e.getIdentifier();
        if (!id.namespace().equals(NAMESPACE) || !id.value().startsWith("log/")) return;
        if (!(e.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        Player p = connection.getPlayer();
        String[] path = id.value().split("/");
        try {
            PlacedBlock computer = plugin.blocks().byId(Integer.parseInt(path[1]));
            // the page buttons carry only ids: check again that this is a computer the player is standing at
            if (computer == null || !computer.type.id().equals("computer") || computer.distanceSquared(p.getLocation()) > 64) return;
            openLog(p, computer, Integer.parseInt(path[2]));
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException ex) {
            // a hand-made packet with a broken path: ignore it
        }
    }
}
