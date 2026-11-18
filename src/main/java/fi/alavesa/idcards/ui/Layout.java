package fi.alavesa.idcards.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import org.bukkit.plugin.Plugin;

/**
 * The geometry the resource pack was generated with (layout.properties, written by tools/gen_pack.py next to the
 * shader's copy of the same numbers), plus the measurements of the card font.
 */
public final class Layout {

    /** A place on the card: where its text starts (card pixels), its scale and how many characters fit. */
    public record Slot(int id, int x, int y, int scale, int maxChars) {
    }

    public record Box(double x0, double y0, double z0, double x1, double y1, double z1) {
    }

    /** What the card font can show besides ASCII 0x20-0x5F. */
    private static final String EXTRA = "ÄÖÅÜÉ";

    private final Properties p = new Properties();
    public final int cardWidth, cardHeight, advance, barsAdvance;
    public final int[] palette;
    public final int ink, label, paper, accent, hint;
    public final Slot art, photo, header, labelName, nameBig, nameSmall, labelTitle, prefix, labelSerial, serial, barcode, footer, hintSlot;

    public Layout(Plugin plugin) throws IOException {
        try (InputStream in = plugin.getResource("layout.properties")) {
            if (in == null) throw new IOException("layout.properties is missing from the plugin jar");
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        cardWidth = integer("card.width");
        cardHeight = integer("card.height");
        advance = integer("font.advance");
        barsAdvance = integer("bars.advance");
        String[] colors = p.getProperty("palette").split(",");
        palette = new int[colors.length];
        for (int i = 0; i < colors.length; i++) palette[i] = Integer.parseInt(colors[i], 16);
        ink = integer("palette.ink");
        label = integer("palette.label");
        paper = integer("palette.paper");
        accent = integer("palette.accent");
        hint = integer("palette.hint");
        art = slot("art");
        photo = slot("photo");
        header = slot("header");
        labelName = slot("label_name");
        nameBig = slot("name_big");
        nameSmall = slot("name_small");
        labelTitle = slot("label_title");
        prefix = slot("prefix");
        labelSerial = slot("label_serial");
        serial = slot("serial");
        barcode = slot("barcode");
        footer = slot("footer");
        hintSlot = slot("hint");
    }

    private int integer(String key) {
        return Integer.parseInt(p.getProperty(key).trim());
    }

    public double[] numbers(String key) {
        String[] parts = p.getProperty(key).split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Double.parseDouble(parts[i].trim());
        return out;
    }

    public Box box(String key) {
        double[] n = numbers(key);
        return new Box(n[0], n[1], n[2], n[3], n[4], n[5]);
    }

    private Slot slot(String name) {
        double[] n = numbers("slot." + name);
        return new Slot((int) n[0], (int) n[1], (int) n[2], (int) n[3], (int) n[4]);
    }

    // ------------------------------------------------------------------ the card font

    /**
     * What the card font can show: capitals, digits, common punctuation and a few accented capitals. Lower case
     * becomes upper case; anything else becomes '?'. Every character that comes out is one cell wide.
     */
    public static String sanitize(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(upper.length());
        for (int i = 0; i < upper.length(); i++) {
            char ch = upper.charAt(i);
            if (ch >= 0x20 && ch < 0x60 || EXTRA.indexOf(ch) >= 0) out.append(ch);
            else if (ch == '{') out.append('(');
            else if (ch == '}') out.append(')');
            else if (ch == '`' || ch == '´' || ch == '’' || ch == '‘') out.append('\'');
            else if (ch == '~' || ch == '–' || ch == '—') out.append('-');
            else if (ch == '|') out.append('/');
            else if (ch == '“' || ch == '”') out.append('"');
            else if (!Character.isLowSurrogate(ch)) out.append('?');
        }
        return out.toString();
    }

    /** Width in font pixels of text that went through {@link #sanitize}: the font is monospace by construction. */
    public int width(String sanitized) {
        return sanitized.length() * advance;
    }

    /** The longest beginning of {@code sanitized} that is at most {@code maxPixels} wide. */
    public String fit(String sanitized, int maxPixels) {
        int n = sanitized.length();
        while (n > 0 && width(sanitized.substring(0, n)) > maxPixels) n--;
        return sanitized.substring(0, n);
    }

    /** Pixels a slot has for text, in font pixels. */
    public int room(Slot slot) {
        return slot.maxChars() * advance;
    }

    /** The palette entry closest to an RGB colour. */
    public int nearest(int rgb) {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            long dr = ((rgb >> 16) & 255) - ((palette[i] >> 16) & 255), dg = ((rgb >> 8) & 255) - ((palette[i] >> 8) & 255),
                    db = (rgb & 255) - (palette[i] & 255);
            long d = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }
}
