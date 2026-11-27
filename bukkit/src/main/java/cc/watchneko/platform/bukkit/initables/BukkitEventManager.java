package cc.watchneko.platform.bukkit.initables;

import cc.watchneko.WatchNekoAPI;
import cc.watchneko.checks.impl.baritone.Baritone;
import cc.watchneko.checks.impl.scaffolding.ScaffoldF;
import cc.watchneko.manager.init.start.StartableInitable;
import cc.watchneko.platform.bukkit.WatchNekoBukkitLoaderPlugin;
import cc.watchneko.platform.bukkit.events.AutoTotemListener;
import cc.watchneko.platform.bukkit.events.ChestStealerListener;
import cc.watchneko.platform.bukkit.events.DamageMitigationEvent;
import cc.watchneko.platform.bukkit.events.PistonEvent;
import cc.watchneko.platform.bukkit.events.XRayListener;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.LogUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.UUID;

public class BukkitEventManager implements StartableInitable, Listener {
    public void start() {
        LogUtil.info("Registering Bukkit piston, damage mitigation and block placement events");

        Bukkit.getPluginManager().registerEvents(new PistonEvent(), WatchNekoBukkitLoaderPlugin.LOADER);
        Bukkit.getPluginManager().registerEvents(new DamageMitigationEvent(), WatchNekoBukkitLoaderPlugin.LOADER);
        Bukkit.getPluginManager().registerEvents(this, WatchNekoBukkitLoaderPlugin.LOADER);
        Bukkit.getPluginManager().registerEvents(new XRayListener(), WatchNekoBukkitLoaderPlugin.LOADER);
        Bukkit.getPluginManager().registerEvents(new ChestStealerListener(), WatchNekoBukkitLoaderPlugin.LOADER);
        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_11)) {
            Bukkit.getPluginManager().registerEvents(new AutoTotemListener(), WatchNekoBukkitLoaderPlugin.LOADER);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!event.canBuild()) {
            return;
        }
        User user = PacketEvents.getAPI().getPlayerManager().getUser(event.getPlayer());
        PlayerData player = user == null ? null : WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
        if (player == null) {
            return;
        }
        Baritone baritone = player.checkManager.getBaritone();
        ScaffoldF scaffold = player.checkManager.getCheck(ScaffoldF.class);
        boolean baritoneEnabled = baritone.isConfiguredEnabled();
        boolean scaffoldEnabled = scaffold.isConfiguredEnabled();
        if (!baritoneEnabled && !scaffoldEnabled) {
            return;
        }
        Block support = event.getBlockAgainst();
        Block placed = event.getBlockPlaced();
        UUID world = placed.getWorld().getUID();
        Vector3i supportPosition = new Vector3i(support.getX(), support.getY(), support.getZ());
        Vector3i placedPosition = new Vector3i(placed.getX(), placed.getY(), placed.getZ());
        long now = System.nanoTime();
        player.runSafely(() -> {
            if (baritoneEnabled) baritone.onPlaceConfirmed(world, supportPosition, placedPosition, now);
            if (scaffoldEnabled) scaffold.onPlaceConfirmed(world, supportPosition, placedPosition, now);
        });
    }
}
