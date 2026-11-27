package cc.watchneko.checks.impl.crash;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;

import java.util.concurrent.TimeUnit;

@CheckData(name = "CrashJ", description = "Book edit rate and packet size", experimental = true, setback = -1)
public final class CrashJ extends ExperimentalCrashCheck {
    private RateWindow edits;
    private int maxEdits;
    private int maxBytes;
    private long minEditInterval;
    private long lastEdit;
    private boolean hasEdit;

    public CrashJ(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        maxEdits = setting(config, "max-edits-per-second", 10, 1000);
        maxBytes = setting(config, "max-edit-bytes", 98304, 2 * 1024 * 1024);
        minEditInterval = TimeUnit.MILLISECONDS.toNanos(Math.max(0,
                Math.min(5000, config.getIntElse("CrashJ.min-edit-interval-ms", 100))));
        edits = new RateWindow();
        hasEdit = false;
    }

    @Override
    void checkPacket(PacketReceiveEvent event) {
        int bytes;
        if (event.getPacketType() == PacketType.Play.Client.EDIT_BOOK) {
            bytes = ByteBufHelper.readableBytes(event.getByteBuf());
        } else if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE
                && event.getServerVersion().isOlderThan(ServerVersion.V_1_13)) {
            var message = new WrapperPlayClientPluginMessage(event);
            if (!isLegacyBookChannel(message.getChannelName())) return;
            bytes = message.getData().length;
        } else {
            return;
        }

        if (bytes > maxBytes) {
            reject(event, "book-size bytes=" + bytes);
            return;
        }
        long now = System.nanoTime();
        if (limit(event, edits, now, 1, maxEdits, "book-edit-rate")) return;
        if (hasEdit && now - lastEdit < minEditInterval) {
            reject(event, "book-edit-interval");
            return;
        }
        lastEdit = now;
        hasEdit = true;
    }

    static boolean isLegacyBookChannel(String channel) {
        return "MC|BEdit".equals(channel) || "MC|BSign".equals(channel);
    }
}
