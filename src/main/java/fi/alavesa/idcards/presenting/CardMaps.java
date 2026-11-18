package fi.alavesa.idcards.presenting;

import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.luckperms.PrefixFormatter;
import fi.alavesa.idcards.ui.CardView;
import fi.alavesa.idcards.ui.Layout;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import javax.imageio.ImageIO;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.jspecify.annotations.Nullable;

/**
 * The card as a map picture, for the holder's own first-person view while presenting.
 *
 * <p>Vanilla draws any item that carries a map id as a sheet held in BOTH of the player's own, skinned arms. That is
 * the only way a server can show a player their own two arms holding something, so the presenting holder is shown
 * (client-side only) an item with a map id, and the map's picture is the card: face, name, prefix, serial and
 * barcode drawn here into 128x128 map pixels.
 *
 * <p>Map ids are a world resource that is never given back, so they are pooled: one per player presenting at the
 * same moment, remembered in maps.yml and reused for ever.
 */
public final class CardMaps {

    private static final int W = 128, CARD_Y = 24, CARD_H = 80;
    private static final int PAPER = 0xDEE2E6, BAND = 0x24292F, EDGE = 0x14171B, SLEEVE = 0x2F353C, FRAME = 0x787F8A;

    private final IdCardsPlugin plugin;
    private final File file;
    private final List<Integer> known = new ArrayList<>();
    private final Deque<Integer> free = new ArrayDeque<>();
    private @Nullable BufferedImage sheet;

    public CardMaps(IdCardsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "maps.yml");
        if (file.exists()) known.addAll(YamlConfiguration.loadConfiguration(file).getIntegerList("maps"));
        free.addAll(known);
        try (InputStream in = plugin.getResource("resourcepack/assets/idcards/textures/font/ui.png")) {
            if (in != null) sheet = ImageIO.read(in);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "The card font could not be read; hand-held cards show no text", e);
        }
    }

    /** A map showing {@code view}; null when the server refuses to make maps. */
    @SuppressWarnings("deprecation") // Bukkit.getMap(int): map ids are ints in the item component too
    public @Nullable MapView acquire(CardView view) {
        MapView map = null;
        while (map == null && !free.isEmpty()) map = Bukkit.getMap(free.pop());
        if (map == null) {
            if (Bukkit.getWorlds().isEmpty()) return null;
            map = Bukkit.createMap(Bukkit.getWorlds().getFirst());
            known.add(map.getId());
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("maps", known);
            try {
                yaml.save(file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "maps.yml could not be saved; map ids will not be reused after a restart", e);
            }
        }
        map.setTrackingPosition(false);
        map.setUnlimitedTracking(false);
        map.setLocked(true);
        paint(map, view);
        return map;
    }

    public void release(MapView map) {
        for (MapRenderer r : List.copyOf(map.getRenderers())) map.removeRenderer(r);
        free.push(map.getId());
    }

    /** Replaces the picture (a prefix, name or skin changed). Send the map again afterwards. */
    public void paint(MapView map, CardView view) {
        for (MapRenderer r : List.copyOf(map.getRenderers())) map.removeRenderer(r);
        BufferedImage image = draw(view);
        map.addRenderer(new MapRenderer(false) {
            private boolean drawn;

            @Override
            public void render(MapView m, MapCanvas canvas, Player player) {
                if (drawn) return;
                drawn = true;
                for (int y = 0; y < W; y++) {
                    for (int x = 0; x < W; x++) canvas.setPixelColor(x, y, new Color(image.getRGB(x, y)));
                }
            }
        });
    }

    private BufferedImage draw(CardView view) {
        Layout layout = plugin.layout();
        BufferedImage img = new BufferedImage(W, W, BufferedImage.TYPE_INT_RGB);
        fill(img, 0, 0, W, W, SLEEVE);                                   // the badge sleeve around the card
        fill(img, 2, 2, W - 4, W - 4, 0x3A4149);
        fill(img, 52, 6, 24, 6, EDGE);                                   // the clip slot
        int y0 = CARD_Y;
        fill(img, 3, y0 - 1, W - 6, CARD_H + 2, EDGE);
        fill(img, 4, y0, W - 8, CARD_H, PAPER);
        fill(img, 4, y0, W - 8, 12, BAND);
        fill(img, 4, y0 + 12, W - 8, 2, layout.palette[layout.accent]);
        text(img, 8, y0 + 2, plugin.lang().raw("card.header-short"), layout.palette[layout.paper], 19);
        // face, 8x8 at 3x
        int fx = 8, fy = y0 + 19;
        fill(img, fx - 1, fy - 1, 26, 26, EDGE);
        int[] face = plugin.profiles().face(view.owner());
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) fill(img, fx + x * 3, fy + y * 3, 3, 3, face == null ? FRAME : face[y * 8 + x] & 0xFFFFFF);
        }
        text(img, 38, y0 + 19, view.name(), layout.palette[layout.ink], 14);
        fill(img, 36, y0 + 30, 86, 11, BAND);
        int x = 38, left = 14;
        for (PrefixFormatter.Run run : view.prefix()) {
            String s = Layout.sanitize(run.text());
            if (s.length() > left) s = s.substring(0, left);
            text(img, x, y0 + 32, s, run.color() == null ? layout.palette[layout.paper] : run.color().value(), left);
            x += s.length() * layout.advance;
            left -= s.length();
            if (left <= 0) break;
        }
        text(img, 38, y0 + 45, view.serial(), 0x6B7480, 14);
        for (int i = 0; i < 56; i++) {                                   // barcode
            if (((view.barcode() >>> (i % 60)) & 1) != 0) fill(img, 8 + i * 2, y0 + 60, 2, 14, EDGE);
        }
        return img;
    }

    private static void fill(BufferedImage img, int x0, int y0, int w, int h, int rgb) {
        for (int y = Math.max(0, y0); y < Math.min(W, y0 + h); y++) {
            for (int x = Math.max(0, x0); x < Math.min(W, x0 + w); x++) img.setRGB(x, y, rgb);
        }
    }

    /** Draws with the pack's own card font sheet (16 columns, starting at the space character). */
    private void text(BufferedImage img, int x0, int y0, String raw, int rgb, int maxChars) {
        if (sheet == null) return;
        String s = Layout.sanitize(raw);
        if (s.length() > maxChars) s = s.substring(0, maxChars);
        for (int i = 0; i < s.length(); i++) {
            int index = glyphIndex(s.charAt(i));
            int cx = (index % 16) * 5, cy = (index / 16) * 8;
            if (cy + 8 > sheet.getHeight()) continue;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 5; x++) {
                    if ((sheet.getRGB(cx + x, cy + y) >>> 24) > 127) {
                        int px = x0 + i * 6 + x, py = y0 + y;
                        if (px >= 0 && px < W && py >= 0 && py < W) img.setRGB(px, py, rgb);
                    }
                }
            }
        }
    }

    private static int glyphIndex(char ch) {
        if (ch >= 0x20 && ch < 0x60) return ch - 0x20;
        int extra = "ÄÖÅÜÉ".indexOf(ch);
        return extra < 0 ? '?' - 0x20 : 0x40 + extra;
    }
}
