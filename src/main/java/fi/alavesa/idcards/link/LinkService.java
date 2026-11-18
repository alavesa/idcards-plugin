package fi.alavesa.idcards.link;

import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.block.PlacedBlock;
import fi.alavesa.idcards.config.Lang;
import fi.alavesa.idcards.util.Items;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Which scanner reports to which computer (many to many), and the Link Tool that edits it.
 *
 * <p>Tool: right-click a scanner (it is remembered), then right-click a computer: linked. Sneak + right-click a
 * scanner or a computer: all its links are removed. Links live in the links table and die with either block.
 */
public final class LinkService implements Listener {

    public static final String TOOL = "linktool";
    public static final String SCANNER = "scanner", COMPUTER = "computer";

    private final IdCardsPlugin plugin;
    private final Map<Integer, Set<Integer>> byScanner = new HashMap<>(), byComputer = new HashMap<>();
    private final Map<UUID, Integer> selected = new HashMap<>();

    public LinkService(IdCardsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Call after the blocks are loaded: links to blocks that no longer exist are dropped. */
    public void load() {
        for (int[] row : plugin.db().load("SELECT scanner, computer FROM links", rs -> new int[]{rs.getInt(1), rs.getInt(2)})) {
            if (plugin.blocks().byId(row[0]) == null || plugin.blocks().byId(row[1]) == null) {
                plugin.db().write("DELETE FROM links WHERE scanner = ? AND computer = ?", row[0], row[1]);
                continue;
            }
            byScanner.computeIfAbsent(row[0], k -> new HashSet<>()).add(row[1]);
            byComputer.computeIfAbsent(row[1], k -> new HashSet<>()).add(row[0]);
        }
    }

    public ItemStack createTool() {
        return Items.tagged(plugin, TOOL, "link_tool");
    }

    public boolean isTool(ItemStack item) {
        return TOOL.equals(plugin.keys().itemType(item));
    }

    public List<PlacedBlock> computersOf(PlacedBlock scanner) {
        return resolve(byScanner.get(scanner.id));
    }

    public List<PlacedBlock> scannersOf(PlacedBlock computer) {
        return resolve(byComputer.get(computer.id));
    }

    private List<PlacedBlock> resolve(Set<Integer> ids) {
        List<PlacedBlock> out = new ArrayList<>();
        if (ids != null) {
            for (int id : ids) {
                PlacedBlock b = plugin.blocks().byId(id);
                if (b != null) out.add(b);
            }
        }
        return out;
    }

    /** Returns false when the two were linked already. */
    public boolean link(PlacedBlock scanner, PlacedBlock computer) {
        if (!byScanner.computeIfAbsent(scanner.id, k -> new HashSet<>()).add(computer.id)) return false;
        byComputer.computeIfAbsent(computer.id, k -> new HashSet<>()).add(scanner.id);
        plugin.db().write("INSERT OR IGNORE INTO links (scanner, computer) VALUES (?,?)", scanner.id, computer.id);
        plugin.computers().refreshAll(); // an idle screen says whether a scanner is linked
        return true;
    }

    /** Removes every link of a scanner or computer; returns how many there were. */
    public int unlinkAll(PlacedBlock block) {
        boolean scanner = block.type.id().equals(SCANNER);
        Set<Integer> others = (scanner ? byScanner : byComputer).remove(block.id);
        if (others != null) {
            for (int other : others) {
                Set<Integer> back = (scanner ? byComputer : byScanner).get(other);
                if (back != null) back.remove(block.id);
            }
        }
        plugin.db().write("DELETE FROM links WHERE " + (scanner ? "scanner" : "computer") + " = ?", block.id);
        selected.values().removeIf(id -> id == block.id);
        if (others != null && !others.isEmpty()) plugin.computers().refreshAll();
        return others == null ? 0 : others.size();
    }

    /** computer.auto-link: a new block links to every counterpart within the radius. */
    public void autoLink(PlacedBlock placed, Player by) {
        double radius = plugin.settings().computerAutoLinkRadius;
        if (radius <= 0) return;
        boolean scanner = placed.type.id().equals(SCANNER);
        var counterpart = plugin.blocks().type(scanner ? COMPUTER : SCANNER);
        int n = 0;
        for (PlacedBlock other : plugin.blocks().near(placed.center(), radius, counterpart)) {
            if (scanner ? link(placed, other) : link(other, placed)) n++;
        }
        if (n > 0) plugin.lang().send(by, "link.auto", Lang.ph("count", n));
    }

    /** A right-click on a scanner or computer with the tool in hand. */
    public void click(Player p, PlacedBlock block) {
        Lang lang = plugin.lang();
        if (!p.hasPermission("idcard.link")) {
            lang.send(p, "link.no-permission");
            return;
        }
        if (p.isSneaking()) {
            lang.send(p, "link.unlinked", Lang.ph("block", block.label()), Lang.ph("count", unlinkAll(block)));
            p.playSound(p.getLocation(), "idcards:link", 1f, 0.7f);
            return;
        }
        if (block.type.id().equals(SCANNER)) {
            selected.put(p.getUniqueId(), block.id);
            lang.send(p, "link.selected", Lang.ph("scanner", block.label()));
            p.playSound(p.getLocation(), "idcards:link", 1f, 1f);
            return;
        }
        Integer scannerId = selected.get(p.getUniqueId());
        PlacedBlock scanner = scannerId == null ? null : plugin.blocks().byId(scannerId);
        if (scanner == null) {
            lang.send(p, "link.select-first");
            return;
        }
        if (link(scanner, block)) {
            lang.send(p, "link.linked", Lang.ph("scanner", scanner.label()), Lang.ph("computer", block.label()));
            p.playSound(p.getLocation(), "idcards:link", 1f, 1.3f);
        } else {
            lang.send(p, "link.already", Lang.ph("scanner", scanner.label()), Lang.ph("computer", block.label()));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        selected.remove(e.getPlayer().getUniqueId());
    }
}
