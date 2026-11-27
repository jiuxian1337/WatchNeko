package cc.watchneko.manager.init.stop;

import cc.watchneko.WatchNekoAPI;
import cc.watchneko.events.packets.ViaMovementInput;
import cc.watchneko.utils.anticheat.LogUtil;
import com.github.retrooper.packetevents.PacketEvents;

public class TerminatePacketEvents implements StoppableInitable {
    @Override
    public void stop() {
        LogUtil.info("Terminating PacketEvents...");
        WatchNekoAPI.INSTANCE.getPlayerDataManager().getEntries().forEach(ViaMovementInput::remove);
        PacketEvents.getAPI().terminate();
    }
}
