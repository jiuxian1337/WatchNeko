package cc.watchneko.platform.bukkit.initables;

import cc.watchneko.WatchNekoAPI;
import cc.watchneko.manager.init.start.AbstractTickEndEvent;
import cc.watchneko.platform.api.Platform;
import cc.watchneko.platform.bukkit.utils.reflection.PaperUtils;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.LogUtil;
import cc.watchneko.utils.lists.HookedListWrapper;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.util.reflection.Reflection;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

// Copied from: https://github.com/ThomasOM/Pledge/blob/master/src/main/java/dev/thomazz/pledge/inject/ServerInjector.java
@SuppressWarnings(value = {"unchecked", "deprecated"})
public class BukkitTickEndEvent extends AbstractTickEndEvent implements Listener {

    @Override
    public void start() {
        if (!super.shouldInjectEndTick()) {
            return;
        }
        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThan(ServerVersion.V_1_11_2) && !Boolean.getBoolean("paper.explicit-flush")) {
            LogUtil.warn("Reach.enable-post-packet=true but paper.explicit-flush=false, add \"-Dpaper.explicit-flush=true\" to your server's startup flags for fully functional extra reach accuracy.");
        }
        // this is necessary for folia
        if (WatchNekoAPI.INSTANCE.getPlatform() == Platform.FOLIA) {
            // Folia has no connection list to inject into, so the event is the only route. Failing to
            // register it used to be silent, which left `enable-post-packet` on with nothing behind it.
            if (!PaperUtils.registerTickEndEvent(this, this::tickAllFoliaPlayers)) {
                LogUtil.error("Failed to register the end-of-tick event on Folia; post-packet reach checking"
                        + " is inactive even though Reach.enable-post-packet is true");
            }
            return;
        }
        // if it fails to inject, try to use paper's event
        if (!injectWithReflection() && !PaperUtils.registerTickEndEvent(this, this::tickAllPlayers)) {
            LogUtil.error("Failed to inject into the end of tick event!");
        }
    }

    private void tickAllPlayers() {
        for (PlayerData player : WatchNekoAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            if (player.disableGrim) continue;
            super.onEndOfTick(player);
        }
    }

    /**
     * Sweeps the players this region owns.
     *
     * <p>Folia fires {@code ServerTickEndEvent} once per <em>region</em> per tick, from
     * {@code MinecraftServer#tickServer(..., region)}, which {@code TickRegions.TickRegionData#tickRegion}
     * calls — so this runs on the region thread that is currently ticking, with
     * {@code TickRegionScheduler.setTickingRegion} already done. That is what makes the ownership check
     * meaningful: {@code isOwnedByCurrentRegion} resolves to {@code TickThread.isTickThreadFor}, which
     * compares the current thread's region against the entity's. Each player is therefore swept exactly
     * once, by the region that owns them.
     */
    private void tickAllFoliaPlayers() {
        for (PlayerData player : WatchNekoAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            if (player.disableGrim) continue;
            // Guard the field that is actually dereferenced. `platformPlayer` and `bukkitPlayer` are set
            // together, but getFromUUID can hand back a player while Bukkit.getPlayer returns null.
            final Player bukkitPlayer = player.bukkitPlayer;
            if (bukkitPlayer == null) continue;
            if (!Bukkit.isOwnedByCurrentRegion(bukkitPlayer)) continue;
            super.onEndOfTick(player);
        }
    }

    /**
     * Replaces the server's connection list with a wrapper that fires this plugin's sweep whenever the
     * server iterates it at the end of a tick.
     *
     * @return whether the injection happened; {@code false} means the caller should fall back to the
     *         Paper event
     */
    private boolean injectWithReflection() {
        // Inject so we can add the final transaction pre-flush event
        try {
            Object connection = SpigotReflectionUtil.getMinecraftServerConnectionInstance();

            Field connectionsList = Reflection.getField(connection.getClass(), List.class, 1);
            if (connectionsList == null) {
                LogUtil.error("Could not find the server's connection list to inject into");
                return false;
            }
            List<Object> endOfTickObject = (List<Object>) connectionsList.get(connection);

            // Use a list wrapper to check when the size method is called
            // Unsure why synchronized is needed because the object itself gets synchronized
            // but whatever.  At least plugins can't break it, I guess.
            //
            // Pledge injects into another list, so we should be safe injecting into this one
            List<?> wrapper = Collections.synchronizedList(new HookedListWrapper<>(endOfTickObject) {
                @Override
                public void onIterator() {
                    tickAllPlayers();
                }
            });

            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);
            unsafe.putObject(connection, unsafe.objectFieldOffset(connectionsList), wrapper);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LogUtil.error("Failed to inject into the end of tick event via reflection", e);
            return false;
        } catch (RuntimeException e) {
            // Anything else — a field that moved, a mapping change, an Unsafe that refuses the write —
            // used to escape start() entirely and skip the Paper-event fallback that exists for exactly
            // this case. A failed injection is a reason to try the other route, not to give up.
            LogUtil.error("Failed to inject into the end of tick event via reflection", e);
            return false;
        }
        return true;
    }
}
