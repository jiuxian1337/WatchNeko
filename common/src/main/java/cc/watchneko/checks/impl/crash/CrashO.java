package cc.watchneko.checks.impl.crash;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.common.client.WrapperCommonClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@CheckData(name = "CrashO", description = "Plugin message channel and registration limits", experimental = true, setback = -1)
public final class CrashO extends ExperimentalCrashCheck {
    private static final Pattern CHANNEL_KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private int maxPayloadBytes;
    private int maxChannels;
    private Set<String> blockedChannels;

    public CrashO(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        maxPayloadBytes = setting(config, "max-payload-bytes", 32767, 2 * 1024 * 1024);
        maxChannels = setting(config, "max-registered-channels-per-packet", 128, 4096);
        blockedChannels = Set.copyOf(config.getStringListElse("CrashO.blocked-channels", List.of()));
    }

    @Override
    void checkPacket(PacketReceiveEvent event) {
        WrapperCommonClientPluginMessage<?> message;
        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            message = new WrapperPlayClientPluginMessage(event);
        } else if (event.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            message = new WrapperConfigClientPluginMessage(event);
        } else {
            return;
        }
        String channel = message.getChannelName();
        boolean legacy = event.getServerVersion().isOlderThan(ServerVersion.V_1_13);
        byte[] payload = message.getData();
        if (!validChannel(channel, legacy) && !"BungeeCord".equals(channel)) {
            reject(event, "plugin-channel-format");
        } else if (payload.length > maxPayloadBytes && !(legacy && CrashJ.isLegacyBookChannel(channel))) {
            reject(event, "plugin-payload-size bytes=" + payload.length);
        } else if (isRegistration(channel) && !validRegistration(payload, legacy, maxChannels)) {
            reject(event, "plugin-channel-registration");
        } else if (blockedChannels.contains(channel)) {
            reject(event, "plugin-channel-restricted");
        }
    }

    private static boolean isRegistration(String channel) {
        return "REGISTER".equals(channel) || "UNREGISTER".equals(channel)
                || "minecraft:register".equals(channel) || "minecraft:unregister".equals(channel);
    }

    static boolean validRegistration(byte[] payload, boolean legacy, int maximum) {
        final String names;
        try {
            names = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException ex) {
            return false;
        }
        if (names.isEmpty()) return true;
        int start = 0;
        int count = 0;
        while (start < names.length()) {
            int separator = names.indexOf(0, start);
            int end = separator < 0 ? names.length() : separator;
            if (++count > maximum || !validChannel(names.substring(start, end), legacy)) return false;
            if (separator < 0) return true;
            start = separator + 1;
        }
        return true;
    }

    private static boolean validChannel(String channel, boolean legacy) {
        if (channel == null || channel.isEmpty()) return false;
        if (!legacy) return CHANNEL_KEY.matcher(channel).matches();
        return channel.length() <= 20 && channel.chars().noneMatch(Character::isISOControl);
    }
}
