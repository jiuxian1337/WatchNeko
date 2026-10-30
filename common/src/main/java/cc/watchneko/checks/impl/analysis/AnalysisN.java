package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;

/**
 * The perfect-entropy verdict of {@link AnalysisM}.
 *
 * <p>Upstream blocks attacks for 5 s on this path and adds no VL, so it belongs in the {@code NoPenalty}
 * group like the other silent heuristics.
 */
@CheckData(
        name = "AnalysisN",
        configName = "AnalysisN",
        description = "identical shannon entropy across windows",
        decay = 0.05
)
public final class AnalysisN extends Check {

    public long mitigateDamageMs = 5000L;

    public AnalysisN(final PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(final ConfigManager config) {
        mitigateDamageMs = Math.max(0L, (long) config.getDoubleElse("AnalysisN.mitigate-damage-ms", mitigateDamageMs));
    }
}
