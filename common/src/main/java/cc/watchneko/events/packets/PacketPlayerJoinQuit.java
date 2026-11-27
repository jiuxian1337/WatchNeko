package cc.watchneko.events.packets;

import cc.watchneko.WatchNekoAPI;
import cc.watchneko.platform.api.player.PlatformPlayer;
import cc.watchneko.utils.anticheat.LogUtil;
import cc.watchneko.utils.reflection.ViaVersionUtil;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;


public class PacketPlayerJoinQuit extends PacketListenerAbstract {

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Login.Server.LOGIN_SUCCESS) {
            // Do this after send to avoid sending packets before the PLAY state
            event.getTasksAfterSend().add(() -> WatchNekoAPI.INSTANCE.getPlayerDataManager().addUser(event.getUser()));
        }
    }

    @Override
    public void onUserConnect(UserConnectEvent event) {
        // Player connected too soon, perhaps late bind is off
        // Don't kick everyone on reload
        if (event.getUser().getConnectionState() == ConnectionState.PLAY && !WatchNekoAPI.INSTANCE.getPlayerDataManager().exemptUsers.contains(event.getUser())) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onUserLogin(UserLoginEvent event) {
        Object nativePlayerObject = Objects.requireNonNull(event.getPlayer());

        // This will never throw a NPE because code is run in OnUserConnect -> onPacketSend -> OnUserLogin order
        // And the user will be added to the map before the getPlayer() method call
        @NonNull PlatformPlayer platformPlayer = WatchNekoAPI.INSTANCE.getPlatformPlayerFactory().getFromNativePlayerType(nativePlayerObject);
        var trackedPlayer = WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (trackedPlayer != null && ViaVersionUtil.isAvailable) ViaMovementInput.install(trackedPlayer);

        if (WatchNekoAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("debug-pipeline-on-join", false)) {
            LogUtil.info("Pipeline: " + ChannelHelper.pipelineHandlerNamesAsString(event.getUser().getChannel()));
        }
        if (platformPlayer.hasPermission("watchneko.alerts.enable-on-join") && platformPlayer.hasPermission("watchneko.alerts")) {
            WatchNekoAPI.INSTANCE.getAlertManager().toggleAlerts(platformPlayer, platformPlayer.hasPermission("watchneko.alerts.enable-on-join.silent"));
        }
        if (platformPlayer.hasPermission("watchneko.verbose.enable-on-join") && platformPlayer.hasPermission("watchneko.verbose")) {
            WatchNekoAPI.INSTANCE.getAlertManager().toggleVerbose(platformPlayer, platformPlayer.hasPermission("watchneko.verbose.enable-on-join.silent"));
        }
        if (platformPlayer.hasPermission("watchneko.brand.enable-on-join") && platformPlayer.hasPermission("watchneko.brand")) {
            WatchNekoAPI.INSTANCE.getAlertManager().toggleBrands(platformPlayer, platformPlayer.hasPermission("watchneko.brand.enable-on-join.silent"));
        }
        if (platformPlayer.hasPermission("watchneko.spectate") && WatchNekoAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("spectators.hide-regardless", false)) {
            WatchNekoAPI.INSTANCE.getSpectateManager().onLogin(platformPlayer.getUniqueId());
        }
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        WatchNekoAPI.INSTANCE.getPlayerDataManager().onDisconnect(event.getUser());
    }
}
