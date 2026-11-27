package cc.watchneko.events.packets;

import cc.watchneko.WatchNekoAPI;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;

/** Runs before WatchNeko's inventory and payload listeners decode client data. */
public final class ExperimentalCrashListener extends PacketListenerAbstract {
    public ExperimentalCrashListener() {
        super(PacketListenerPriority.LOWEST);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.isCancelled() || (event.getConnectionState() != ConnectionState.PLAY
                && event.getConnectionState() != ConnectionState.CONFIGURATION)) return;
        var player = WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (player != null && player.isExperimentalChecks() && !player.disableGrim) {
            player.checkManager.onExperimentalCrashReceive(event);
        }
    }
}
