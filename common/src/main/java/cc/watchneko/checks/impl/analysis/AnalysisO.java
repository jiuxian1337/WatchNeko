package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;

/**
 * The invalid-distinct verdict of {@link AnalysisM}.
 *
 * <p>Upstream adds VL for this path and blocks attacks for 3 s, so it keeps both here.
 */
@CheckData(
        name = "AnalysisO",
        configName = "AnalysisO",
        description = "too few distinct rotation values",
        decay = 0.05
)
public final class AnalysisO extends Check {

    public long mitigateDamageMs = 3000L;

    public AnalysisO(final PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(final ConfigManager config) {
        mitigateDamageMs = Math.max(0L, (long) config.getDoubleElse("AnalysisO.mitigate-damage-ms", mitigateDamageMs));
    }
}
