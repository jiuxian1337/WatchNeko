package cc.watchneko.platform.bukkit;

import cc.watchneko.WatchNekoAPI;
import cc.watchneko.platform.api.Platform;
import cc.watchneko.platform.api.PlatformServer;
import cc.watchneko.platform.api.sender.Sender;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;


public class BukkitPlatformServer implements PlatformServer {

    @Override
    public String getPlatformImplementationString() {
        return Bukkit.getVersion();
    }

    @Override
    public void dispatchCommand(Sender sender, String command) {
        CommandSender commandSender = WatchNekoBukkitLoaderPlugin.LOADER.getBukkitSenderFactory().reverse(sender);
        Bukkit.dispatchCommand(commandSender, command);
    }

    @Override
    public Sender getConsoleSender() {
        return WatchNekoBukkitLoaderPlugin.LOADER.getBukkitSenderFactory().map(Bukkit.getConsoleSender());
    }

    @Override
    public void registerOutgoingPluginChannel(String name) {
        WatchNekoBukkitLoaderPlugin.LOADER.getServer().getMessenger().registerOutgoingPluginChannel(WatchNekoBukkitLoaderPlugin.LOADER, name);
    }

    @Override
    public double getTPS() {
        if (WatchNekoAPI.INSTANCE.getPlatform() == Platform.FOLIA) {
            // Folia exposes region-local TPS, while packet checks build alerts on the Netty thread.
            if (!Bukkit.isPrimaryThread()) return Double.NaN;
            try {
                return Bukkit.getTPS()[0];
            } catch (UnsupportedOperationException ignored) {
                return Double.NaN;
            }
        }
        return SpigotReflectionUtil.getTPS();
    }
}
