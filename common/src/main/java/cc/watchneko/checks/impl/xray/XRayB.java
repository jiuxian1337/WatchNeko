package cc.watchneko.checks.impl.xray;

import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.util.Vector3i;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@CheckData(name = "XRayB", alternativeName = "XRayE", description = "Irregular excavation paths between newly exposed ore veins", setback = -1)
public final class XRayB extends Check {
    private final PathState path = new PathState();

    public XRayB(PlayerData player) {
        super(player);
    }

    void observe(XRaySettings.Path config, UUID world, Vector3i position, long now, MiningSample sample) {
        Result result = path.observe(config, position, now, sample);
        if (result == null) return;
        MiningPath.Metrics metrics = result.metrics();
        double factor = 100.0 / metrics.blocks();
        flagAndAlert(String.format(Locale.ROOT,
                "world=%s pos=%d/%d/%d ore=%s path=%d turns=%d branches=%d y-shifts=%d per-100=%.1f/%.1f/%.1f%s",
                world, position.x, position.y, position.z, sample.material(), metrics.blocks(), metrics.turns(),
                metrics.branches(), metrics.yShifts(), factor * metrics.turns(), factor * metrics.branches(),
                factor * metrics.yShifts(), result.fakeAirRatio() < 0 ? ""
                        : String.format(Locale.ROOT, " artificial-air=%.2f", result.fakeAirRatio())));
    }

    void interruptPath() {
        path.clearPath();
    }

    void resetSampling() {
        path.clearPath();
        path.score = 0;
        path.fakeCaveTriggers = 0;
    }

    record Result(MiningPath.Metrics metrics, double fakeAirRatio) {}

    static final class PathState {
        private final Map<Vector3i, MiningSample.@Nullable AirSample> excavated = new LinkedHashMap<>();
        private Vector3i previousOre;
        private long started;
        private long lastBreak;
        private double score;
        private int fakeCaveTriggers;
        private long fakeCaveStarted;

        @Nullable Result observe(XRaySettings.Path settings, Vector3i position, long now, MiningSample sample) {
            if ((previousOre != null || score != 0) && now - lastBreak >= settings.cleanupNanos()) {
                clearPath();
                score = 0;
            } else if (previousOre != null && now - started >= settings.cleanupNanos()) {
                clearPath();
            }
            lastBreak = now;
            MiningSample.Ore ore = sample.ore();
            if (ore == null || !settings.targets().includes(sample.material())
                    || ore.discovery() == MiningSample.Discovery.PREVIOUS) {
                if (previousOre != null && !excavated.containsKey(position)) {
                    if (excavated.size() == settings.maxBlocks()) clearPath();
                    else excavated.put(position, sample.nearbyAir());
                }
                return null;
            }
            boolean enclosed = ore.discovery() == MiningSample.Discovery.FIRST
                    && ore.pathEnvironment() == MiningSample.Environment.ENCLOSED;
            Result result = enclosed ? evaluate(settings, position, now, ore.reveal()) : null;
            clearPath();
            if (enclosed) {
                previousOre = position;
                started = now;
            }
            return result;
        }

        private @Nullable Result evaluate(XRaySettings.Path settings, Vector3i ore, long now,
                                         @Nullable MiningSample.Reveal reveal) {
            if (previousOre == null || reveal == null || reveal.nanos() < started
                    || !excavated.containsKey(reveal.openedAt()) || excavated.size() < settings.minBlocks()) {
                return null;
            }
            MiningPath.Metrics metrics = MiningPath.measure(previousOre, ore, reveal.openedAt(), excavated.keySet(),
                    settings.yShiftMin());
            if (metrics == null || metrics.blocks() < settings.minBlocks()) return null;
            boolean irregular = !metrics.straight() && (metrics.turns() > settings.turningLimit()
                    || metrics.branches() > settings.branchingLimit() || metrics.yShifts() > settings.yShiftLimit());
            if (!irregular) {
                score = Math.max(0, score - 1);
                return null;
            }
            if (++score < settings.targets().buffer()) return null;
            score = 0;
            return new Result(metrics, fakeAirRatio(settings.fakeCave(), metrics, now));
        }

        private double fakeAirRatio(XRaySettings.FakeCave settings, MiningPath.Metrics metrics, long now) {
            if (!settings.enabled() || metrics.blocks() < settings.minBlocks()) return -1;
            if (fakeCaveTriggers != 0 && now - fakeCaveStarted >= settings.resetNanos()) fakeCaveTriggers = 0;
            if (fakeCaveTriggers >= settings.maxTriggers()) return -1;
            int sampled = 0;
            int artificial = 0;
            int total = 0;
            for (var entry : excavated.entrySet()) {
                MiningSample.AirSample sample = entry.getValue();
                if (sample == null || !metrics.connected().contains(entry.getKey())) continue;
                artificial += sample.artificial();
                total += sample.total();
                if (++sampled >= settings.sampleLimit()) break;
            }
            if (total == 0) return -1;
            double ratio = (double) artificial / total;
            if (ratio < settings.airRatio()) return -1;
            if (fakeCaveTriggers++ == 0) fakeCaveStarted = now;
            return ratio;
        }

        void clearPath() {
            excavated.clear();
            previousOre = null;
        }
    }
}
