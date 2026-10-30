package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;

/**
 * The IQR verdict of {@link AnalysisJ}, as a check of its own.
 *
 * <p>Upstream adds VL for this path but blocks attacks for 0 ms, so the mitigation window here defaults
 * to nothing; the reason it exists as a separate check is the punishments entry, which is what lets an
 * operator silence this one path without touching the other two that share the same sample window.
 */
@CheckData(
        name = "AnalysisK",
        configName = "AnalysisK",
        description = "statistics IQR verdict",
        decay = 0.05
)
public final class AnalysisK extends Check {

    public long mitigateDamageMs;

    public AnalysisK(final PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(final ConfigManager config) {
        mitigateDamageMs = Math.max(0L, (long) config.getDoubleElse("AnalysisK.mitigate-damage-ms", mitigateDamageMs));
    }
}
