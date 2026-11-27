package cc.watchneko.checks.impl.autoclicker;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;

import java.util.Locale;

@CheckData(name = "AutoclickerE", description = "Repeated rapid right-click intervals",
        experimental = true, requiresExperimentalChecks = false, requiresExactPunishmentMatch = true, setback = -1)
public final class AutoclickerE extends Check implements PacketCheck {
    private final RightClicks clicks = new RightClicks();
    private volatile Settings settings;
    private Settings appliedSettings;
    private long lastTeleport;

    public AutoclickerE(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        settings = new Settings(config.getBooleanElse("AutoclickerE.enabled", true));
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        var type = event.getPacketType();
        boolean placement = type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT;
        boolean itemUse = type == PacketType.Play.Client.USE_ITEM;
        Settings current = settings;
        if (!current.enabled() || !isEnabled() || player.disableGrim || isExemptPermission()
                || current != appliedSettings || lastTeleport != player.lastServerTeleport
                || player.packetStateData.lastPacketWasTeleport || player.inVehicle()
                || type == PacketType.Play.Client.HELD_ITEM_CHANGE || type == PacketType.Play.Client.CLICK_WINDOW
                || type == PacketType.Play.Client.PLAYER_DIGGING) {
            appliedSettings = current;
            lastTeleport = player.lastServerTeleport;
            clicks.reset();
            return;
        }
        if ((!placement && !itemUse) || event.isCancelled()) return;
        if (player.gamemode != GameMode.SURVIVAL || !player.isTickingReliablyFor(3)
                || !player.openWindow.mustBeClosed() || player.inventory.getHeldItem().isEmpty()) {
            clicks.reset();
            return;
        }
        Mode mode = Mode.ITEM;
        InteractionHand hand;
        if (placement) {
            var place = new WrapperPlayClientPlayerBlockPlacement(event);
            hand = place.getHand();
            if (place.getFace() != BlockFace.OTHER) mode = Mode.BLOCK;
        } else {
            hand = new WrapperPlayClientUseItem(event).getHand();
        }
        if (hand != InteractionHand.MAIN_HAND) return;
        Match match = clicks.click(mode, System.nanoTime());
        if (match != null) {
            flagAndAlert(String.format(Locale.ROOT, "mode=%s, mean=%.1fms, deviation=%.2fms",
                    mode.name().toLowerCase(Locale.ROOT), match.mean(), match.deviation()));
        }
    }

    private record Settings(boolean enabled) {}

    enum Mode { ITEM, BLOCK }

    record Match(double mean, double deviation) {}

    static final class RightClicks {
        private final ClickIntervals intervals = new ClickIntervals(32, 14, 140);
        private Mode mode;
        private int matchingWindows;
        private long lastInput;

        Match click(Mode current, long now) {
            // A modern client can fall back from interact-block to use-item in the same click.
            if (mode == Mode.BLOCK && current == Mode.ITEM && now - lastInput >= 0 && now - lastInput < 8_000_000) {
                return null;
            }
            if (current != mode) {
                reset();
                mode = current;
            }
            lastInput = now;
            if (intervals.add(now) == 0) matchingWindows = 0;
            double[] window = intervals.completedWindow();
            if (window == null) return null;
            double sum = 0;
            double squares = 0;
            for (double gap : window) {
                sum += gap;
                squares += gap * gap;
            }
            double mean = sum / window.length;
            double deviation = ClickIntervals.deviation(sum, squares, window.length);
            if (mean > 95 || deviation > 4) {
                matchingWindows = 0;
                return null;
            }
            if (++matchingWindows < 3) return null;
            matchingWindows = 0;
            return new Match(mean, deviation);
        }

        void reset() {
            intervals.reset();
            matchingWindows = 0;
            mode = null;
        }
    }
}
