package fi.alavesa.idcards.computer;

import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.block.BlockManager;
import fi.alavesa.idcards.block.PlacedBlock;
import fi.alavesa.idcards.luckperms.PrefixFormatter;
import fi.alavesa.idcards.scanner.ScanRecord;
import fi.alavesa.idcards.ui.CardView;
import fi.alavesa.idcards.ui.Layout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The picture on one computer's screen: a handful of text_display entities placed on the glass of the model.
 * Rendering only; what to show is decided by {@link ComputerService}.
 *
 * <p>All text is drawn in the card font, at full brightness
 * (the displays carry a brightness override of 15/15, so the screen reads the same in a dark room), without shadow
 * or background. The face is the player-head text object of the card owner, scaled up.
 *
 * <p>Geometry: a text display draws 1 font pixel as 1/40 block times its scale, centred on the entity, growing
 * upwards from it. The screen is {@code computer.screen} in layout.properties (model pixels); seen from the front,
 * the reader's left is the model's high x.
 */
final class Screen {

    private static final TextColor PHOSPHOR = TextColor.color(0x7DFF99), DIM = TextColor.color(0x3F9A58), WHITE = TextColor.color(0xE8FFE8),
            GRANTED = TextColor.color(0x55FF6A), DENIED = TextColor.color(0xFF5548);
    private static final float TEXT = 0.2f, BIG = 0.4f, HEAD = 0.85f;
    /** How far in front of the glass the text floats, model pixels. */
    private static final double LIFT = 0.12;

    private final IdCardsPlugin plugin;
    private final PlacedBlock block;
    private final double left, top, glass, width;
    private final TextDisplay head, title, line1, line2, line3, line4, status;
    private @Nullable BukkitTask timeout;

    Screen(IdCardsPlugin plugin, PlacedBlock block, BlockManager.Spawner spawner) {
        this.plugin = plugin;
        this.block = block;
        double[] s = plugin.layout().numbers("computer.screen"); // x0, y0, x1, y1, z
        left = s[2] - 0.5;
        top = s[3] - 0.45;
        width = s[2] - s[0] - 1.0;
        glass = s[4] - LIFT;
        title = make(spawner, TEXT);
        head = make(spawner, HEAD);
        line1 = make(spawner, TEXT);
        line2 = make(spawner, TEXT);
        line3 = make(spawner, TEXT);
        line4 = make(spawner, TEXT);
        status = make(spawner, BIG);
    }

    private TextDisplay make(BlockManager.Spawner spawner, float scale) {
        TextDisplay d = spawner.textDisplay(block.local(8, 8, glass));
        d.setBillboard(Display.Billboard.FIXED);
        d.setBrightness(new Display.Brightness(15, 15));
        d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        d.setDefaultBackground(false);
        d.setShadowed(false);
        d.setLineWidth(10000);
        d.setAlignment(TextDisplay.TextAlignment.LEFT);
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale), new Quaternionf()));
        d.text(Component.empty());
        return d;
    }

    /** Model pixels covered by {@code fontPixels} at a text scale. */
    private static double px(double fontPixels, float scale) {
        return fontPixels * scale / 40.0 * 16.0;
    }

    /**
     * Puts a line where its left edge is {@code fromLeft} model pixels right of the screen's left edge and its top
     * {@code fromTop} below the screen's top. {@code widthFont}: the text's width in font pixels.
     */
    private void place(TextDisplay d, Component text, int widthFont, float scale, double fromLeft, double fromTop) {
        double x = left - fromLeft - px(widthFont, scale) / 2.0, y = top - fromTop - px(8, scale);
        Location at = block.local(x, y, glass);
        if (d.isValid()) {
            d.teleport(at);
            d.text(text);
        }
    }

    private void placeCentered(TextDisplay d, Component text, int widthFont, float scale, double fromTop) {
        place(d, text, widthFont, scale, (width - px(widthFont, scale)) / 2.0, fromTop);
    }

    private Component font(String sanitized, TextColor color) {
        return Component.text(sanitized, color).font(Layout.FONT).shadowColor(ShadowColor.none());
    }

    private void line(TextDisplay d, String raw, TextColor color, float scale, double fromLeft, double fromTop, int maxChars) {
        String s = plugin.layout().fit(Layout.sanitize(raw), maxChars * plugin.layout().advance);
        place(d, font(s, color), plugin.layout().width(s), scale, fromLeft, fromTop);
    }

    private void clear(TextDisplay... displays) {
        for (TextDisplay d : displays) if (d.isValid()) d.text(Component.empty());
    }

    void cancelTimeout() {
        if (timeout != null) timeout.cancel();
        timeout = null;
    }

    /** Nothing scanned (yet, or for a while). */
    void idle(int linkedScanners) {
        cancelTimeout();
        Layout layout = plugin.layout();
        clear(head, line3, line4, status);
        String t = Layout.sanitize(plugin.lang().raw("computer.screen.title")), l = block.label();
        placeCentered(title, font(t, PHOSPHOR), layout.width(t), TEXT, 0.0);
        String waiting = Layout.sanitize(plugin.lang().raw(linkedScanners == 0 ? "computer.screen.no-scanner" : "computer.screen.idle"));
        placeCentered(line1, font(waiting, linkedScanners == 0 ? DIM : PHOSPHOR), layout.width(waiting), TEXT, 3.2);
        placeCentered(line2, font(Layout.sanitize(l), DIM), layout.width(Layout.sanitize(l)), TEXT, 6.6);
    }

    /** A scan, shown until the timeout, then {@code afterTimeout} runs (back to idle). */
    void show(ScanRecord record, Runnable afterTimeout) {
        cancelTimeout();
        Layout layout = plugin.layout();
        String t = Layout.sanitize(plugin.lang().raw("computer.screen.title"));
        placeCentered(title, font(t, DIM), layout.width(t), TEXT, 0.0);
        double textLeft = px(8, HEAD) + 0.7, row = 1.25;
        int columns = (int) ((width - textLeft) / px(layout.advance, TEXT));
        if (record.owner() != null) {
            CardView view = plugin.views().of(record.owner());
            // the head glyph: 8 font pixels, no font of ours involved
            place(head, Component.object(view.head()).shadowColor(ShadowColor.none()), 8, HEAD, 0.0, row);
            line(line1, view.name(), WHITE, TEXT, textLeft, row + 0.1, columns);
            TextComponent.Builder prefix = Component.text();
            int left = columns, widthFont = 0;
            for (PrefixFormatter.Run run : view.prefix()) {
                String s = layout.fit(Layout.sanitize(run.text()), left * layout.advance);
                if (s.isEmpty()) continue;
                prefix.append(font(s, run.color() == null ? PHOSPHOR : run.color()));
                left -= s.length();
                widthFont += layout.width(s);
            }
            place(line2, prefix.build(), widthFont, TEXT, textLeft, row + 1.15);
        } else {
            clear(head);
            line(line1, plugin.lang().raw("computer.screen.unreadable"), DENIED, TEXT, textLeft, row + 0.1, columns);
            clear(line2);
        }
        int full = (int) (width / px(layout.advance, TEXT));
        line(line3, time(record.at()), PHOSPHOR, TEXT, 0.0, row + 3.0, full);
        line(line4, record.scannerName(), PHOSPHOR, TEXT, 0.0, row + 3.95, full);
        String verdict = Layout.sanitize(plugin.lang().raw(record.granted() ? "computer.screen.granted" : "computer.screen.denied"));
        placeCentered(status, font(verdict, record.granted() ? GRANTED : DENIED), layout.width(verdict), BIG, row + 5.2);
        int seconds = plugin.settings().computerScreenTimeoutSeconds;
        if (seconds > 0) timeout = Bukkit.getScheduler().runTaskLater(plugin, afterTimeout, seconds * 20L);
    }

    private String time(long at) {
        return ComputerService.formatTime(plugin, at);
    }

    static String formatTime(String pattern, String zone, long at) {
        ZoneId z;
        try {
            z = zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
        } catch (java.time.DateTimeException e) {
            z = ZoneId.systemDefault();
        }
        try {
            return DateTimeFormatter.ofPattern(pattern).withZone(z).format(Instant.ofEpochMilli(at));
        } catch (IllegalArgumentException e) {
            return DateTimeFormatter.ofPattern("HH:mm:ss").withZone(z).format(Instant.ofEpochMilli(at));
        }
    }
}
