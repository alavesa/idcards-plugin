package fi.alavesa.idcards.ui;

import fi.alavesa.idcards.config.Lang;
import net.kyori.adventure.text.Component;

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
    public Component render(CardView view, boolean inspect) {
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
}
