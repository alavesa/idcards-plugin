package fi.alavesa.idcards.api;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.Nullable;

/**
 * A card is being scanned. Fired before the swipe animation starts. Listeners may flip the result with
 * {@link #setGranted} (for example to add clearance rules of their own), or cancel the event, in which case the
 * scanner does nothing at all.
 */
public final class CardScanEvent extends PlayerEvent implements Cancellable {

    /** Why the plugin itself granted or denied. */
    public enum Reason { VALID, MALFORMED, FORGED, REVOKED }

    private static final HandlerList HANDLERS = new HandlerList();
    private final int scannerId;
    private final String scannerName;
    private final Location scannerLocation;
    private final @Nullable UUID cardOwner;
    private final Reason reason;
    private boolean granted, cancelled;

    public CardScanEvent(Player holder, int scannerId, String scannerName, Location scannerLocation, @Nullable UUID cardOwner, Reason reason) {
        super(holder);
        this.scannerId = scannerId;
        this.scannerName = scannerName;
        this.scannerLocation = scannerLocation;
        this.cardOwner = cardOwner;
        this.reason = reason;
        this.granted = reason == Reason.VALID;
    }

    public int getScannerId() {
        return scannerId;
    }

    public String getScannerName() {
        return scannerName;
    }

    public Location getScannerLocation() {
        return scannerLocation.clone();
    }

    /** The UUID the card is bound to; null when the card's data could not be read. */
    public @Nullable UUID getCardOwner() {
        return cardOwner;
    }

    public Reason getReason() {
        return reason;
    }

    public boolean isGranted() {
        return granted;
    }

    public void setGranted(boolean granted) {
        this.granted = granted;
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
