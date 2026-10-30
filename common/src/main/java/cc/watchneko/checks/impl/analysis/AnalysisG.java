package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.analysis.aim.AimHeuristicGate;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.math.Vec2f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pattern detection from MX's {@code AimPatternCheck}: over one hundred samples, look for the same
 * three-delta sequence repeating, or for consecutive deltas that are numerically identical.
 *
 * <p>This one does add global VL upstream, so it stays in the {@code Analysis} group and also suppresses
 * outgoing damage for five seconds, which is upstream's block duration.
 */
@CheckData(
        name = "AnalysisG",
        configName = "AnalysisG",
        description = "repeating or identical rotation delta sequences",
        decay = 0.05
)
public final class AnalysisG extends Check implements RotationCheck {

    private static final int PATTERN_LENGTH = 3;
    private static final int SAMPLE_SIZE = 100;
    private static final int MIN_START_INDEX_GAP = PATTERN_LENGTH;

    private final List<Vec2f> sample = new ArrayList<>();

    private Vec2f oldDelta = new Vec2f(0, 0);
    private float buffer;

    private float bufferLimit = 2.5f;
    private float bufferFade = 0.3f;
    private long mitigateDamageMs = 5000L;

    public AnalysisG(final PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(final ConfigManager config) {
        bufferLimit = (float) config.getDoubleElse("AnalysisG.buffer", bufferLimit);
        bufferFade = (float) config.getDoubleElse("AnalysisG.buffer-fade", bufferFade);
        mitigateDamageMs = Math.max(0L, (long) config.getDoubleElse("AnalysisG.mitigate-damage-ms", mitigateDamageMs));
    }

    @Override
    public void process(final RotationUpdate update) {
        if (AimHeuristicGate.blocks(player)) {
            return;
        }
        if (AimHeuristicGate.noPitchChange(update)) {
            return;
        }

        boolean flagged = false;
        final Vec2f delta = AimHeuristicGate.referenceDelta(update);
        final Vec2f vec = new Vec2f(delta.x() - oldDelta.x(), delta.y() - oldDelta.y());

        sample.add(vec);
        if (sample.size() >= SAMPLE_SIZE) {
            final List<Vec2f> patterns = new ArrayList<>();
            final List<Float> rawPatterns = new ArrayList<>();
            final List<Float> filteredPatterns = new ArrayList<>();

            for (int i = 0; i < SAMPLE_SIZE; i++) {
                if (i > 0 && Math.abs(sample.get(i).x()) > 1.0) {
                    rawPatterns.add(Math.abs(sample.get(i).x() - sample.get(i - 1).y()));
                }
            }
            for (final float x : rawPatterns) {
                if (x < 1e-4) filteredPatterns.add(x);
            }

            if (filteredPatterns.size() > 3) {
                flagged = true;
                if (buffer++ >= bufferLimit) {
                    if (flagAndAlert("suspicious patterns: " + filteredPatterns) && mitigateDamageMs > 0) {
                        player.mitigateDamage(mitigateDamageMs);
                    }
                    buffer -= 1;
                }
            }

            if (!flagged) {
                final int currentSampleSize = sample.size();
                for (int i = 0; i <= currentSampleSize - PATTERN_LENGTH; ++i) {
                    for (int j = i + MIN_START_INDEX_GAP; j <= currentSampleSize - PATTERN_LENGTH; ++j) {
                        Vec2f pattern = null;
                        for (int k = 0; k < PATTERN_LENGTH; ++k) {
                            final Vec2f first = sample.get(i + k);
                            final Vec2f second = sample.get(j + k);
                            if (Objects.equals(first, second)) {
                                pattern = first;
                                break;
                            }
                        }
                        if (pattern != null && !patterns.contains(pattern)) patterns.add(pattern);
                    }
                }
                for (final Vec2f vec2f : patterns) {
                    final float x = Math.abs(vec2f.x());
                    final float y = Math.abs(vec2f.y());
                    if ((x > 1.0 || y > 1.0) && (x > 0.26 && y > 0.26)) {
                        flagged = true;
                        if (buffer++ >= bufferLimit) {
                            if (flagAndAlert("suspicious pattern: " + vec2f) && mitigateDamageMs > 0) {
                                player.mitigateDamage(mitigateDamageMs);
                            }
                            buffer -= 1f;
                        }
                        break;
                    }
                }
            }

            if (!flagged) buffer = Math.max(0, buffer - bufferFade);
            sample.clear();
        }
        oldDelta = delta;
    }
}
