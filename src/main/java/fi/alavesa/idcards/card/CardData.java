package fi.alavesa.idcards.card;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What reading an item as a card gave. {@code owner} is set whenever the item names one, valid or not. */
public record CardData(Status status, @Nullable UUID owner, @Nullable UUID cardId, long issuedAt) {

    public enum Status {
        /** Bound to a player, signed by this server and not replaced by a newer card. */
        VALID,
        /** Not one of this plugin's cards at all. */
        NOT_A_CARD,
        /** Tagged as a card but the data is missing or unreadable. */
        MALFORMED,
        /** The data does not carry this server's signature (edited in creative mode, copied from elsewhere). */
        FORGED,
        /** A real card that a newer card of the same owner replaced (card.one-per-player). */
        REVOKED
    }

    public boolean isCard() {
        return status != Status.NOT_A_CARD;
    }

    public boolean valid() {
        return status == Status.VALID;
    }

    static final CardData NONE = new CardData(Status.NOT_A_CARD, null, null, 0);
}
