package cc.watchneko.checks.impl.crash;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;

@CheckData(name = "CrashN", description = "Oversized incoming packet", experimental = true, setback = -1)
public final class CrashN extends ExperimentalCrashCheck {
    private int maxBytes;

    public CrashN(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        maxBytes = setting(config, "max-packet-bytes", 256 * 1024, 8 * 1024 * 1024);
    }

    @Override
    void checkPacket(PacketReceiveEvent event) {
        // PacketEvents has already received the frame, but has not decoded the payload for our checks.
        int bytes = ByteBufHelper.readableBytes(event.getByteBuf());
        if (bytes > maxBytes) reject(event, "packet-size type=" + event.getPacketType() + " bytes=" + bytes);
    }

    public boolean discardBeforeTranslation(int bytes) {
        if (bytes <= maxBytes || !active() || !shouldModifyPackets() || isNoModifyPacketPermission()) return false;
        if (!flagAndAlert("pre-translation-packet-size bytes=" + bytes)) return false;
        player.onPacketCancel();
        return true;
    }
}
