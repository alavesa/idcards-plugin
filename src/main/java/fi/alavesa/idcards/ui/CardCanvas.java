package fi.alavesa.idcards.ui;

import fi.alavesa.idcards.luckperms.PrefixFormatter;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.object.ObjectContents;

/**
 * Lays one card out inside ONE text component (the name of a boss bar). Pure text building: no Bukkit, no state.
 *
 * <p>How it works (font idcards:ui from tools/gen_pack.py; shaders/include/idcards_text_*.glsl):
 * <ul>
 *   <li>every character advances exactly {@code advance} px and the font has signed spaces of ±2^n px, so the pen
 *       can be put anywhere;</li>
 *   <li>the component ends with the pen back at 0: its width is 0, so vanilla's centring puts pen 0 at the screen
 *       centre and the shader reads a glyph's distance from the centre as its x on the card;</li>
 *   <li>the colour of every part is the marker (red 252, green 0xB8-0xBF) carrying slot, palette colour and mode;
 *       shadows are off, or a dark copy of every glyph would be drawn too.</li>
 * </ul>
 * A slot's text starts at card x = slot.x; the shader multiplies pen positions by the slot's scale, so the pen is put
 * at slot.x / scale (the generator only uses x values that divide evenly).
 */
public final class CardCanvas {

    public static final Key FONT = Key.key("idcards", "ui");
    private static final char SPACE_POS = '', SPACE_NEG = '', CHAR_CARD = '', CHAR_BARS = '';
    /** A player-head glyph is 8 px wide and has no spacing column. */
    private static final int HEAD_ADVANCE = 8;

    private final Layout layout;
    private final int mode;
    private final List<Component> parts = new ArrayList<>();
    private int pen;

    /** @param inspect false: the holder's own full-screen card. true: the side view of a card somebody presents. */
    public CardCanvas(Layout layout, boolean inspect) {
        this.layout = layout;
        this.mode = inspect ? 1 : 0;
    }

    /** The card background. Call first: glyphs are drawn in text order. */
    public CardCanvas art() {
        move(layout.art, 0);
        parts.add(Component.text(String.valueOf(CHAR_CARD), marker(layout.art, 0)).font(FONT).shadowColor(ShadowColor.none()));
        pen += layout.cardWidth + 1;
        return this;
    }

    /** Text in one palette colour, cut to what the slot holds. */
    public CardCanvas text(Layout.Slot slot, String text, int palette) {
        String s = layout.fit(Layout.sanitize(text), layout.room(slot));
        if (s.isEmpty()) return this;
        move(slot, 0);
        parts.add(Component.text(s, marker(slot, palette)).font(FONT).shadowColor(ShadowColor.none()));
        pen += layout.width(s);
        return this;
    }

    /** Coloured runs (a LuckPerms prefix), cut to what the slot holds; a run without a colour gets {@code fallback}. */
    public CardCanvas runs(Layout.Slot slot, List<PrefixFormatter.Run> runs, int fallback) {
        int left = layout.room(slot), offset = 0;
        for (PrefixFormatter.Run run : runs) {
            String s = layout.fit(Layout.sanitize(run.text()), left);
            if (s.isEmpty()) continue;
            move(slot, offset);
            int palette = run.color() == null ? fallback : layout.nearest(run.color().value());
            parts.add(Component.text(s, marker(slot, palette)).font(FONT).shadowColor(ShadowColor.none()));
            int w = layout.width(s);
            pen += w;
            offset += w;
            left -= w;
            if (left <= 0) break;
        }
        return this;
    }

    /** The face: a player-head object glyph, which the shader enlarges to the photo size. */
    public CardCanvas head(ObjectContents head) {
        move(layout.photo, 0);
        parts.add(Component.object(head).color(marker(layout.photo, 0)).shadowColor(ShadowColor.none()));
        pen += HEAD_ADVANCE;
        return this;
    }

    /** A barcode: 3 bits per glyph, {@code maxChars} glyphs. */
    public CardCanvas barcode(long bits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < layout.barcode.maxChars(); i++) sb.append((char) (CHAR_BARS + (int) ((bits >>> (i * 3)) & 7)));
        move(layout.barcode, 0);
        parts.add(Component.text(sb.toString(), marker(layout.barcode, layout.ink)).font(FONT).shadowColor(ShadowColor.none()));
        pen += sb.length() * layout.barsAdvance;
        return this;
    }

    public Component build() {
        TextComponent.Builder out = Component.text();
        for (Component part : parts) out.append(part);
        // back to pen 0: the whole component is then 0 px wide (see the class comment)
        if (pen != 0) out.append(Component.text(shift(-pen)).font(FONT));
        return out.build();
    }

    /** Puts the pen where the slot's text starts, plus {@code offset} font pixels. */
    private void move(Layout.Slot slot, int offset) {
        int target = slot.x() / slot.scale() + offset;
        if (target != pen) parts.add(Component.text(shift(target - pen)).font(FONT));
        pen = target;
    }

    private TextColor marker(Layout.Slot slot, int palette) {
        int data = (slot.id() & 31) | ((palette & 31) << 5) | (mode << 10);
        return TextColor.color(252, 0xB8 | (data >> 8), data & 255);
    }

    private static String shift(int px) {
        StringBuilder out = new StringBuilder();
        char base = px < 0 ? SPACE_NEG : SPACE_POS;
        int n = Math.abs(px);
        for (int bit = 9; bit >= 0; bit--) if ((n & (1 << bit)) != 0) out.append((char) (base + bit));
        return out.toString();
    }
}
