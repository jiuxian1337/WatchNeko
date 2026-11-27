package cc.watchneko.checks.impl.crash;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTabComplete;

import java.util.List;
import java.util.Locale;

@CheckData(name = "CrashR", description = "Invalid or restricted tab-completion input", experimental = true, setback = -1)
public final class CrashR extends ExperimentalCrashCheck {
    private List<String> blockedPrefixes;
    private List<String> blockedFragments;

    public CrashR(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        blockedPrefixes = normalize(config.getStringListElse("CrashR.blocked-prefixes", List.of()));
        blockedFragments = normalize(config.getStringListElse("CrashR.blocked-fragments", List.of()));
    }

    @Override
    void checkPacket(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.TAB_COMPLETE) return;
        String text = new WrapperPlayClientTabComplete(event).getText();
        for (int index = 0; index < text.length(); index++) {
            if (Character.isISOControl(text.charAt(index))) {
                reject(event, "completion-control-character");
                return;
            }
        }
        if (blockedPrefixes.isEmpty() && blockedFragments.isEmpty()) return;
        String normalized = text.stripLeading().toLowerCase(Locale.ROOT);
        if (blockedPrefixes.stream().anyMatch(normalized::startsWith)
                || blockedFragments.stream().anyMatch(normalized::contains)) {
            reject(event, "completion-restricted");
        }
    }

    private static List<String> normalize(List<String> values) {
        return values.stream().filter(value -> !value.isBlank()).map(value -> value.toLowerCase(Locale.ROOT)).toList();
    }
}
