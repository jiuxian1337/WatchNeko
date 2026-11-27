package cc.watchneko.checks.impl.crash;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;

import java.util.concurrent.TimeUnit;

abstract class ExperimentalCrashCheck extends Check implements PacketCheck {
    ExperimentalCrashCheck(PlayerData player) {
        super(player);
    }

    final boolean active() {
        return isEnabled() && player.isExperimentalChecks() && !player.disableGrim && !isExemptPermission();
    }

    @Override
    public final void onPacketReceive(PacketReceiveEvent event) {
        if (active() && !event.isCancelled()) checkPacket(event);
    }

    abstract void checkPacket(PacketReceiveEvent event);

    final boolean reject(PacketReceiveEvent event, String reason) {
        if (!flagAndAlert(reason)) return false;
        return cancel(event);
    }

    final boolean limit(PacketReceiveEvent event, RateWindow window, long now, int cost, int maximum, String reason) {
        if (!window.overLimit(now, cost, maximum)) return false;
        if (window.blocking) {
            cancel(event);
        } else if (!window.reported) {
            window.reported = true;
            window.blocking = reject(event, reason);
        }
        return true;
    }

    private boolean cancel(PacketReceiveEvent event) {
        if (!shouldModifyPackets() || isNoModifyPacketPermission()) return false;
        event.setCancelled(true);
        player.onPacketCancel();
        return true;
    }

    final int setting(ConfigManager config, String key, int fallback, int upperBound) {
        return Math.max(1, Math.min(upperBound, config.getIntElse(getConfigName() + "." + key, fallback)));
    }

    static int utf8Bytes(String text) {
        if (text == null) return 0;
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x7f) bytes++;
            else if (c <= 0x7ff) bytes += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    static final class RateWindow {
        private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
        private long started;
        private long used;
        private boolean reported;
        private boolean blocking;

        boolean overLimit(long now, int cost, int maximum) {
            if (used == 0 || now - started >= SECOND) {
                used = 0;
                started = now;
                reported = false;
                blocking = false;
            }
            used = Math.min((long) maximum + 1, used + cost);
            return used > maximum;
        }
    }
}
