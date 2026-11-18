package fi.alavesa.idcards.ui;

import fi.alavesa.idcards.config.Lang;
import fi.alavesa.idcards.luckperms.PrefixFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;

/** Turns a {@link CardView} into the text component the shader draws as a card. No game logic. */
public final class CardRenderer {

    private final Layout layout;
    private final Lang lang;

    public CardRenderer(Layout layout, Lang lang) {
        this.layout = layout;
        this.lang = lang;
    }

    /**
     * @param inspect false: full-screen card with the closing hint in the corner; true: the side view shown while
     *                looking at somebody who presents their card
     */
    public Component render(CardView view, boolean inspect, boolean plain) {
        if (plain) return plain(view, inspect);
        CardCanvas c = new CardCanvas(layout, inspect).art().head(view.head());
        c.text(layout.header, lang.raw("card.header"), layout.paper);
        c.text(layout.labelName, lang.raw("card.label-name"), layout.label);
        String name = Layout.sanitize(view.name());
        c.text(layout.width(name) <= layout.room(layout.nameBig) ? layout.nameBig : layout.nameSmall, name, layout.ink);
        c.text(layout.labelTitle, lang.raw("card.label-title"), layout.label);
        c.runs(layout.prefix, view.prefix(), layout.paper);
        c.text(layout.labelSerial, lang.raw("card.label-serial"), layout.label);
        c.text(layout.serial, view.serial(), layout.ink);
        c.barcode(view.barcode());
        c.text(layout.footer, lang.raw("card.footer"), layout.label);
        if (!inspect) c.text(layout.hintSlot, lang.raw("card.hint-close"), layout.hint);
        return c.build();
    }

    /**
     * The same card as one line of ordinary text (/idcard plain): for players without the resource pack, who would
     * see the fancy card as rows of missing-glyph boxes. It stays where vanilla puts a boss bar name.
     */
    private Component plain(CardView view, boolean inspect) {
        TextComponent.Builder prefix = Component.text();
        for (PrefixFormatter.Run run : view.prefix()) prefix.append(Component.text(run.text(), run.color() == null ? NamedTextColor.GRAY : run.color()));
        return lang.get(inspect ? "card.plain-inspect" : "card.plain", Lang.ph("name", view.name()), Lang.ph("prefix", prefix.build()),
                Lang.ph("serial", view.serial()));
    }
}
