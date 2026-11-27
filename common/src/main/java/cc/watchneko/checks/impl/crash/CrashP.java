package cc.watchneko.checks.impl.crash;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;

@CheckData(name = "CrashP", description = "Sign text byte limit", experimental = true, setback = -1)
public final class CrashP extends ExperimentalCrashCheck {
    private int maxLineBytes;

    public CrashP(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        maxLineBytes = setting(config, "max-sign-line-bytes", 384, 4096);
    }

    @Override
    void checkPacket(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
        String[] lines = new WrapperPlayClientUpdateSign(event).getTextLines();
        for (String line : lines) {
            int bytes = utf8Bytes(line);
            if (bytes > maxLineBytes) {
                reject(event, "sign-line-size bytes=" + bytes);
                return;
            }
        }
    }
}
