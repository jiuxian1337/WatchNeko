package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;

/**
 * The z-factor verdict of {@link AnalysisJ}, as a check of its own.
 *
 * <p>Upstream blocks attacks for 5.5 s on this path, the longest of the three, which is why the default
 * window is not zero.
 */
@CheckData(
        name = "AnalysisL",
        configName = "AnalysisL",
        description = "statistics z-factor verdict",
        decay = 0.05
)
public final class AnalysisL extends Check {

    public long mitigateDamageMs = 5500L;

    public AnalysisL(final PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(final ConfigManager config) {
        mitigateDamageMs = Math.max(0L, (long) config.getDoubleElse("AnalysisL.mitigate-damage-ms", mitigateDamageMs));
    }
}
