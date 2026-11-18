package fi.alavesa.idcards.ui;

import fi.alavesa.idcards.IdCardsPlugin;
import fi.alavesa.idcards.luckperms.PrefixFormatter;
import java.util.UUID;

/** Looks up what a card of some owner shows right now. Main thread; never blocks (see ProfileService, PrefixService). */
public final class CardViews {

    private final IdCardsPlugin plugin;

    public CardViews(IdCardsPlugin plugin) {
        this.plugin = plugin;
    }

    public CardView of(UUID owner) {
        String name = plugin.profiles().name(owner);
        if (name == null) name = plugin.lang().raw("card.unknown-name");
        return new CardView(owner, name, PrefixFormatter.runs(plugin.prefixes().get(owner)), CardView.serial(owner),
                CardView.barcode(owner), plugin.profiles().head(owner));
    }
}
