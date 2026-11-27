package cc.watchneko.platform.bukkit.events;

import cc.watchneko.WatchNekoAPI;
import cc.watchneko.checks.impl.combat.CpsLimit;
import cc.watchneko.player.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class DamageMitigationEvent implements Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        PlayerData data = findGrimPlayer(player.getUniqueId());
        if (data == null || data.disableGrim) {
            return;
        }
        double multiplier = data.getMitigateDamageTime() >= System.currentTimeMillis() ? 0.05 : 1;
        if (event.getEntity() instanceof Player && isMelee(event.getCause().name())) {
            double cpsMultiplier = data.checkManager.getCheck(CpsLimit.class).damageMultiplier(player.getWorld().getUID(), System.nanoTime());
            multiplier = Math.min(multiplier, cpsMultiplier);
        }
        if (multiplier < 1) event.setDamage(event.getDamage() * multiplier);
    }

    private static boolean isMelee(String cause) {
        return cause.equals("ENTITY_ATTACK") || cause.equals("ENTITY_SWEEP_ATTACK");
    }

    private PlayerData findGrimPlayer(java.util.UUID uuid) {
        for (PlayerData entry : WatchNekoAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            if (uuid.equals(entry.getUniqueId())) {
                return entry;
            }
        }
        return null;
    }
}
