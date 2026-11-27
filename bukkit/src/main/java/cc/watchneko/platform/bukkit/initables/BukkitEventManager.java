package cc.watchneko.platform.bukkit.initables;

import cc.watchneko.manager.init.start.StartableInitable;
import cc.watchneko.platform.bukkit.WatchNekoBukkitLoaderPlugin;
import cc.watchneko.platform.bukkit.events.AutoTotemListener;
import cc.watchneko.platform.bukkit.events.ChestStealerListener;
import cc.watchneko.platform.bukkit.events.DamageMitigationEvent;
import cc.watchneko.platform.bukkit.events.PistonEvent;
import cc.watchneko.utils.anticheat.LogUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.bukkit.Bukkit;

public class BukkitEventManager implements StartableInitable {
    public void start() {
        LogUtil.info("Registering bukkit events... (PistonEvent, DamageMitigationEvent)");

        Bukkit.getPluginManager().registerEvents(new PistonEvent(), WatchNekoBukkitLoaderPlugin.LOADER);
        Bukkit.getPluginManager().registerEvents(new DamageMitigationEvent(), WatchNekoBukkitLoaderPlugin.LOADER);
        Bukkit.getPluginManager().registerEvents(new ChestStealerListener(), WatchNekoBukkitLoaderPlugin.LOADER);
        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_11)) {
            Bukkit.getPluginManager().registerEvents(new AutoTotemListener(), WatchNekoBukkitLoaderPlugin.LOADER);
        }
    }
}
