package cc.watchneko.checks.impl.scaffolding;

import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.BlockPlaceCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.BlockPlace;
import cc.watchneko.utils.lists.EvictingList;
import cc.watchneko.utils.math.GrimMath;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.world.BlockFace;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

@CheckData(name = "ScaffoldE", description = "Short, consistent sneaks while bridging")
public final class ScaffoldE extends BlockPlaceCheck {
    private final SneakPattern pattern = new SneakPattern();

    public ScaffoldE(PlayerData player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!pattern.bridging) {
            return;
        }
        if (!canTrack()) {
            pattern.reset();
        } else if (!event.isCancelled() && isTickPacket(event.getPacketType())) {
            pattern.tick(System.nanoTime(), player.isSneaking);
        }
    }

    @Override
    public void onBlockPlace(BlockPlace place) {
        if (!canTrack()) {
            pattern.reset();
            return;
        }
        if (!place.isBlock || place.isCancelled() || place.position.y >= player.y) {
            return;
        }
        BlockFace face = place.getFace();
        if (face == BlockFace.UP || face == BlockFace.DOWN || face == BlockFace.OTHER) {
            return;
        }

        Match match = pattern.place(System.nanoTime());
        if (match != null && flagAndAlert(String.format(Locale.ROOT, "std=%.3f, avg=%.3f", match.deviation(), match.average()))) {
            player.resyncPose();
            player.mitigateDamage();
            if (shouldCancel() && shouldModifyPackets()) {
                place.resync();
            }
        }
    }

    private boolean canTrack() {
        return isEnabled() && !player.disableGrim && !isExemptPermission()
                && !player.inVehicle() && !player.isFlying && !player.isGliding
                && !player.packetStateData.lastPacketWasTeleport;
    }

    record Match(double deviation, double average) {
    }

    static final class SneakPattern {
        private static final long BRIDGE_WINDOW = TimeUnit.SECONDS.toNanos(4);
        private static final long RELEASE_DELAY = TimeUnit.MILLISECONDS.toNanos(50);
        private final EvictingList<Integer> samples = new EvictingList<>(25);
        private boolean bridging;
        private long lastPlace;
        private long lastSneak;
        private int sneakTicks;

        Match place(long now) {
            expire(now);
            bridging = true;
            lastPlace = now;
            if (!samples.isFull()) {
                return null;
            }
            double deviation = GrimMath.getStandardDeviation(samples);
            double average = GrimMath.getAverage(samples);
            samples.clear();
            return deviation < 3.15 && average < 3.5 ? new Match(deviation, average) : null;
        }

        void tick(long now, boolean sneaking) {
            expire(now);
            if (!bridging) {
                return;
            }
            if (sneaking) {
                sneakTicks++;
                lastSneak = now;
            } else if (sneakTicks > 0 && now - lastSneak > RELEASE_DELAY) {
                samples.add(sneakTicks);
                sneakTicks = 0;
            }
        }

        private void expire(long now) {
            if (bridging && now - lastPlace >= BRIDGE_WINDOW) {
                reset();
            }
        }

        void reset() {
            bridging = false;
            sneakTicks = 0;
            samples.clear();
        }
    }
}
