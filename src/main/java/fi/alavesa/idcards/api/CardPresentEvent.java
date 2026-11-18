package fi.alavesa.idcards.api;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** A player is about to hold their card out (F key). Cancel it and nothing is shown. */
public final class CardPresentEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID cardOwner;
    private final boolean valid;
    private boolean cancelled;

    public CardPresentEvent(Player presenter, UUID cardOwner, boolean valid) {
        super(presenter);
        this.cardOwner = cardOwner;
        this.valid = valid;
    }

    /** Whose card it is; not necessarily the presenter's own. */
    public UUID getCardOwner() {
        return cardOwner;
    }

    /** False for a forged or revoked card (it can still be held out; scanners deny it). */
    public boolean isCardValid() {
        return valid;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
