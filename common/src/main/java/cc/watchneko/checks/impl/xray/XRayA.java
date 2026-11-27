package cc.watchneko.checks.impl.xray;

import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.util.Vector3i;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@CheckData(name = "XRayA", alternativeName = "XRayD", description = "Repeated discoveries of ore exposed by explosions", setback = -1)
public final class XRayA extends Check {
    private final Window window = new Window();

    public XRayA(PlayerData player) {
        super(player);
    }

    void observe(XRaySettings.Blast config, UUID world, Vector3i position, long now, MiningSample sample) {
        MiningSample.Ore ore = sample.ore();
        if (!config.targets().includes(sample.material()) || ore == null || ore.discovery() != MiningSample.Discovery.FIRST
                || ore.blastEnvironment() != MiningSample.Environment.ENCLOSED || !ore.blastKnown()) return;
        Result result = window.add(now, ore.blastId(), ore.exactBlastMatch(), config);
        if (result != null) {
            flagAndAlert(String.format(Locale.ROOT, "world=%s pos=%d/%d/%d ore=%s hits=%d/%d exact=%d rate=%.3f",
                    world, position.x, position.y, position.z, sample.material(), result.hits, result.samples,
                    result.exact, (double) result.hits / result.samples));
        }
    }

    void resetSampling() {
        window.clear();
        window.score = 0;
    }

    record Result(int hits, int samples, int exact) {}

    static final class Window {
        private final Map<Long, Boolean> blasts = new HashMap<>();
        private int samples;
        private long started;
        private double score;

        Result add(long now, long blastId, boolean exactMatch, XRaySettings.Blast settings) {
            if ((samples != 0 || score != 0) && now - started >= settings.retentionNanos()) {
                clear();
                score = 0;
            }
            if (samples == 0) started = now;
            samples++;
            if (blastId != 0) blasts.merge(blastId, exactMatch, Boolean::logicalOr);
            if (samples < settings.minSamples()) return null;

            int hits = blasts.size();
            Result result = null;
            if (hits >= settings.minHits() && (double) hits / samples > settings.suspiciousRate()) {
                score += hits * settings.penaltyMultiplier();
                if (score >= settings.targets().buffer()) {
                    int exact = (int) blasts.values().stream().filter(Boolean::booleanValue).count();
                    result = new Result(hits, samples, exact);
                    score = 0;
                }
            } else {
                score = Math.max(0, score - 1);
            }
            clear();
            return result;
        }

        private void clear() {
            blasts.clear();
            samples = 0;
        }
    }
}
