package fi.alavesa.idcards.nms;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * The one place that touches server internals: it tells a client that some entities ride a player, without making
 * them ride on the server.
 *
 * <p><b>Why.</b> A card that must stay in front of a moving player has to be attached to them on the CLIENT: a
 * passenger is placed by the client every frame, so it never trails behind, while an entity the server teleports
 * each tick always lags a little and jitters in the holder's own view. But a real passenger changes the player for
 * everyone else: Bukkit refuses to teleport a player who carries passengers, so every other plugin's teleport
 * (elevators, doors, /spawn) would silently fail while a card is held. Sending only the "these ride that" packet
 * gives the smooth attachment and leaves the server-side player untouched.
 *
 * <p><b>How.</b> {@code new ClientboundSetPassengersPacket(player)} (it copies the real passenger list), then the
 * packet's {@code int[]} field is extended with the extra entity ids, and it is sent through
 * {@code ServerPlayer.connection.send}. Reflection on Mojang-mapped names (Paper 26.x runs unobfuscated); nothing is
 * compiled against the server. When a lookup fails {@link #available()} is false and the caller falls back to
 * teleporting the entities every tick.
 */
public final class RideBridge {

    private Constructor<?> packet;
    private Field passengers, connection;
    private Method send;
    private boolean available;

    public RideBridge(Logger log) {
        try {
            Class<?> entity = Class.forName("net.minecraft.world.entity.Entity");
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPassengersPacket");
            packet = packetClass.getConstructor(entity);
            for (Field f : packetClass.getDeclaredFields()) {
                if (f.getType() == int[].class) passengers = f;
            }
            if (passengers == null) throw new NoSuchFieldException("int[] passengers");
            passengers.setAccessible(true);
            connection = Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection");
            send = connection.getType().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log.log(Level.WARNING, "The passenger packet is not reachable on this server build (" + e + "); held cards follow their player by teleports instead");
        }
    }

    public boolean available() {
        return available;
    }

    /** Tells {@code viewer}'s client that {@code riders} (and the vehicle's real passengers) ride {@code vehicle}. */
    public boolean ride(Player viewer, Player vehicle, List<? extends Entity> riders) {
        if (!available) return false;
        try {
            Object handle = vehicle.getClass().getMethod("getHandle").invoke(vehicle);
            Object pkt = packet.newInstance(handle);
            int[] real = (int[]) passengers.get(pkt);
            int[] all = new int[real.length + riders.size()];
            System.arraycopy(real, 0, all, 0, real.length);
            for (int i = 0; i < riders.size(); i++) all[real.length + i] = riders.get(i).getEntityId();
            passengers.set(pkt, all);
            Object to = viewer.getClass().getMethod("getHandle").invoke(viewer);
            send.invoke(connection.get(to), pkt);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
