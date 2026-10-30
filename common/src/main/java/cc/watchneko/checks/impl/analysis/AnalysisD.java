package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;

/**
 * The interpolation verdict of {@link AnalysisB}, split out for the same reason as {@link AnalysisC}.
 *
 * <p>Upstream gives it its own budget (400), its own fade, and a shorter 2.5 s block than the component
 * score, so it must not share {@link AnalysisC}'s config or its mitigation window.
 */
@CheckData(
        name = "AnalysisD",
        configName = "AnalysisD",
        description = "basic heuristic interpolation score",
        decay = 0.05
)
public final class AnalysisD extends Check {

    public long mitigateDamageMs = 2500L;

    public AnalysisD(final PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(final ConfigManager config) {
        mitigateDamageMs = Math.max(0L, (long) config.getDoubleElse("AnalysisD.mitigate-damage-ms", mitigateDamageMs));
    }
}
