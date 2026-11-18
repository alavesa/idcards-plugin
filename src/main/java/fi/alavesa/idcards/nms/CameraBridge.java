package fi.alavesa.idcards.nms;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * The one place that touches server internals: it sends the vanilla "set camera" packet to a single player.
 *
 * <p><b>Why this cannot be done through the API.</b> The only API for it, {@code Player.setSpectatorTarget}, insists
 * on spectator mode, which would make the player invisible, non-solid and unable to hold a card. The packet itself
 * has no such condition: a client in any game mode renders from the entity it names, and while that entity is not
 * the player itself the client ignores movement keys, sends no position or rotation, and mouse movement turns
 * nothing that is visible. That is exactly a camera lock, with zero jitter, which no API call can produce.
 *
 * <p><b>How.</b> Three reflective lookups against Mojang-mapped names (Paper 26.x runs unobfuscated):
 * {@code CraftEntity.getHandle()}, {@code new ClientboundSetCameraPacket(Entity)} and
 * {@code ServerPlayer.connection.send(Packet)}. Nothing is compiled against the server, so the plugin loads on any
 * build; when a lookup fails {@link #available()} is false and the caller uses its API-only fallback.
 */
public final class CameraBridge {

    private Constructor<?> packet;
    private Field connection;
    private Method send;
    private boolean available;

    public CameraBridge(Logger log) {
        try {
            Class<?> entity = Class.forName("net.minecraft.world.entity.Entity");
            packet = Class.forName("net.minecraft.network.protocol.game.ClientboundSetCameraPacket").getConstructor(entity);
            connection = Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection");
            send = connection.getType().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));
            available = true;
        } catch (ReflectiveOperationException | LinkageError e) {
            log.log(Level.WARNING, "The camera packet is not reachable on this server build (" + e + "); the card UI falls back to the seat-and-rotation lock");
        }
    }

    public boolean available() {
        return available;
    }

    /** Makes {@code viewer}'s client render from {@code target}; pass the viewer itself to give the camera back. */
    public boolean setCamera(Player viewer, Entity target) {
        if (!available) return false;
        try {
            Object handle = target.getClass().getMethod("getHandle").invoke(target);
            Object player = viewer.getClass().getMethod("getHandle").invoke(viewer);
            send.invoke(connection.get(player), packet.newInstance(handle));
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
