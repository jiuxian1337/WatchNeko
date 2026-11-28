package cc.watchneko.checks.impl.aim;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;

@CheckData(name = "AimY", description = "Periodic cross-axis rotation disturbance corroborated by braking shape",
        experimental = true, requiresExactPunishmentMatch = true, setback = -1)
public final class AimY extends Check {
    private volatile AimTrajectorySampler.Settings settings;

    public AimY(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        settings = AimTrajectorySampler.Settings.read(config, getConfigName());
    }

    AimTrajectorySampler.Settings settings() {
        return settings;
    }
}
