package cc.watchneko.checks.impl.aim;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;

@CheckData(name = "AimZ", description = "Repeated square-root braking and clipped turn endings",
        experimental = true, requiresExactPunishmentMatch = true, setback = -1)
public final class AimZ extends Check {
    private volatile AimTrajectorySampler.Settings settings;

    public AimZ(PlayerData player) {
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
