package fi.alavesa.idcards.luckperms;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** Where raw prefix strings come from. The only implementation that touches LuckPerms classes is {@link LuckPermsPrefixes}. */
public interface PrefixSource {

    /** The raw prefix of a player, online or not; completes with null when they have none. Safe from any thread. */
    CompletableFuture<@Nullable String> load(UUID player);

    /** Calls back (on any thread) whenever a player's permission data, and so maybe their prefix, changed. */
    void onChange(Consumer<UUID> listener);

    void close();
}
