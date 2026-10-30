package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;

/**
 * The "component" verdict of {@link AnalysisB}, as a check of its own so that it can be judged differently.
 *
 * <p>Upstream punishes this path without adding global VL and with a 3.5 s attack block. Both of those are
 * policy, not arithmetic, and policy belongs to the punishments file: this class flags, the {@code NoPenalty}
 * group decides that a flag costs nothing but an alert, and the mitigation window lives in
 * {@link #mitigateDamageMs}. Keeping it separate from {@link AnalysisB} is what lets an operator tune one of
 * the three basic paths without touching the other two.
 *
 * <p>It has no listener; {@link AnalysisB} calls it. That is the same shape as {@code Hitboxes}, which
 * {@code Reach} drives.
 */
@CheckData(
        name = "AnalysisC",
        configName = "AnalysisC",
        description = "basic heuristic component score",
        decay = 0.05
)
public final class AnalysisC extends Check {

    public long mitigateDamageMs = 3500L;

    public AnalysisC(final PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(final ConfigManager config) {
        mitigateDamageMs = Math.max(0L, (long) config.getDoubleElse("AnalysisC.mitigate-damage-ms", mitigateDamageMs));
    }
}
