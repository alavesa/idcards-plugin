package fi.alavesa.idcards.luckperms;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Prefixes from LuckPerms. This class is only loaded when the LuckPerms plugin is present (see
 * {@link PrefixService}), so the plugin runs without it.
 *
 * <p>Online users are already in LuckPerms' memory; offline users are loaded by LuckPerms on its own threads
 * ({@code UserManager.loadUser} returns a future), so nothing here blocks the server thread.
 */
public final class LuckPermsPrefixes implements PrefixSource {

    private final Plugin plugin;
    private final LuckPerms api = LuckPermsProvider.get();
    private @Nullable EventSubscription<UserDataRecalculateEvent> subscription;

    public LuckPermsPrefixes(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public CompletableFuture<@Nullable String> load(UUID player) {
        User loaded = api.getUserManager().getUser(player);
        if (loaded != null) return CompletableFuture.completedFuture(prefix(loaded));
        return api.getUserManager().loadUser(player).thenApply(LuckPermsPrefixes::prefix);
    }

    private static @Nullable String prefix(@Nullable User user) {
        return user == null ? null : user.getCachedData().getMetaData().getPrefix();
    }

    @Override
    public void onChange(Consumer<UUID> listener) {
        // fired after any change that can affect a user's prefix: their own nodes, their groups' nodes, group weights
        subscription = api.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class, e -> listener.accept(e.getUser().getUniqueId()));
    }

    @Override
    public void close() {
        if (subscription != null) subscription.close();
        subscription = null;
    }
}
