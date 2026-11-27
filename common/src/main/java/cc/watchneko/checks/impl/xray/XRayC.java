package cc.watchneko.checks.impl.xray;

import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.util.Vector3i;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@CheckData(name = "XRayC", alternativeName = "XRayF", description = "Consecutive discoveries of small ore veins", setback = -1)
public final class XRayC extends Check {
    private static final long IDLE_EXPIRY = TimeUnit.MINUTES.toNanos(15);
    private final Streak streak = new Streak();

    public XRayC(PlayerData player) {
        super(player);
    }

    void observe(XRaySettings.SmallVeins config, UUID world, Vector3i position, long now, MiningSample sample) {
        MiningSample.Ore ore = sample.ore();
        if (!config.targets().includes(sample.material()) || ore == null) return;
        if (streak.add(ore, now, IDLE_EXPIRY, config)) {
            flagAndAlert("world=" + world + " pos=" + position.x + "/" + position.y + "/" + position.z
                    + " ore=" + sample.material() + " streak=" + config.triggerCount() + " size=" + ore.size()
                    + " face-connected-size=" + ore.faceConnectedSize());
        }
    }

    void interruptStreak() {
        streak.consecutive = 0;
    }

    void resetSampling() {
        streak.consecutive = 0;
        streak.score = 0;
    }

    static final class Streak {
        private int consecutive;
        private long lastDiscovery;
        private double score;

        boolean add(MiningSample.Ore ore, long now, long expiry, XRaySettings.SmallVeins settings) {
            if (ore.discovery() == MiningSample.Discovery.PREVIOUS) return false;
            if ((consecutive != 0 || score != 0) && now - lastDiscovery >= expiry) {
                consecutive = 0;
                score = 0;
            }
            lastDiscovery = now;
            if (ore.discovery() != MiningSample.Discovery.FIRST || ore.smallVeinEnvironment() != MiningSample.Environment.ENCLOSED
                    || ore.size() >= settings.smallSize()) {
                consecutive = 0;
                return false;
            }
            if (++consecutive < settings.triggerCount()) return false;
            consecutive = 0;
            if (++score < settings.targets().buffer()) return false;
            score = 0;
            return true;
        }
    }
}
