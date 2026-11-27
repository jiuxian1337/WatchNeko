package cc.watchneko.checks.impl.scaffolding;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.BlockPlace;
import cc.watchneko.utils.math.MathUtil;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.util.Vector3i;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@CheckData(name = "ScaffoldF", description = "Repeated rapid placements against recently placed supports",
        experimental = true, requiresExperimentalChecks = false, requiresExactPunishmentMatch = true, setback = -1)
public final class ScaffoldF extends Check {
    private static final long MAX_CONFIRMATION_AGE = TimeUnit.MILLISECONDS.toNanos(750);
    private final ArrayDeque<Placement> requests = new ArrayDeque<>();
    private final Chain chain = new Chain();
    private volatile Settings settings;
    private Settings appliedSettings;
    private UUID world;
    private long lastTeleport;
    private long lastPlacementRotation;

    public ScaffoldF(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        settings = new Settings(config.getBooleanElse("ScaffoldF.enabled", true));
    }

    public boolean isConfiguredEnabled() {
        return settings.enabled();
    }

    public void onPlaceRequest(BlockPlace place) {
        if (!updateContext() || !player.checkManager.getBlockPlaceCheck(ScaffoldB.class).isBridgePlacement(place)) return;
        long now = System.nanoTime();
        discardExpired(now);
        if (requests.size() == 32) requests.removeFirst();
        boolean grounded = player.onGround && player.lastOnGround && !player.isSneaking
                && player.isTickingReliablyFor(3) && player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.SPEED).isEmpty();
        long rotation = player.rotationSequence.position();
        float turn = 0;
        if (rotation != lastPlacementRotation) {
            turn = Math.abs(MathUtil.getAngleDifference(player.xRot, player.lastXRot)) + Math.abs(player.yRot - player.lastYRot);
        }
        lastPlacementRotation = rotation;
        requests.addLast(new Placement(place.position, place.getPlacedBlockPos(), now, grounded, turn));
    }

    public void onPlaceConfirmed(UUID eventWorld, Vector3i support, Vector3i placed, long now) {
        if (!updateContext() || !world.equals(eventWorld)) return;
        discardExpired(now);
        Placement confirmed = null;
        boolean ambiguous = false;
        for (Iterator<Placement> iterator = requests.iterator(); iterator.hasNext(); ) {
            Placement request = iterator.next();
            if (now - request.nanos() < 0) break;
            if (request.support().equals(support) && request.placed().equals(placed)) {
                iterator.remove();
                ambiguous |= confirmed != null;
                confirmed = request;
            }
        }
        if (confirmed == null || ambiguous) {
            chain.reset();
            return;
        }
        Match match = chain.accept(confirmed);
        if (match != null) {
            flagAndAlert(String.format(Locale.ROOT, "placements=%d, score=%.2f", match.placements(), match.score()));
        }
    }

    private void discardExpired(long now) {
        while (!requests.isEmpty() && now - requests.peekFirst().nanos() > MAX_CONFIRMATION_AGE) {
            requests.removeFirst();
        }
    }

    private boolean updateContext() {
        Settings current = settings;
        if (!current.enabled() || !isEnabled() || isExemptPermission()
                || player.disableGrim || player.inVehicle() || player.isFlying || player.isGliding
                || player.gamemode != GameMode.SURVIVAL || player.packetStateData.lastPacketWasTeleport
                || player.getSetbackTeleportUtil().shouldBlockMovement()) {
            clear();
            return false;
        }
        UUID currentWorld = player.getWorldUID();
        if (current != appliedSettings || currentWorld == null || !currentWorld.equals(world)
                || player.lastServerTeleport != lastTeleport) {
            clear();
            appliedSettings = current;
            world = currentWorld;
            lastTeleport = player.lastServerTeleport;
        }
        return world != null;
    }

    private void clear() {
        requests.clear();
        chain.reset();
        lastPlacementRotation = player.rotationSequence.position();
    }

    private record Settings(boolean enabled) {}

    record Placement(Vector3i support, Vector3i placed, long nanos, boolean grounded, float turn) {}

    record Match(int placements, double score) {}

    static final class Chain {
        private Placement last;
        private Placement previous;
        private int count;
        private double score;

        Match accept(Placement placement) {
            long gap = last == null ? 0 : placement.nanos() - last.nanos();
            if (gap <= 0 || gap > TimeUnit.MILLISECONDS.toNanos(450) || !continuesBridge(placement)) {
                reset();
                last = placement;
                count = 1;
                return null;
            }

            double millis = gap / 1_000_000.0;
            score = Math.max(0, score - millis / 160.0);
            if (placement.grounded() && last.grounded() && millis >= 20 && millis <= 160) {
                double weight = placement.turn() >= 25 ? 2.0 : 1.15;
                if (placement.placed().x - placement.support().x != last.placed().x - last.support().x
                        || placement.placed().z - placement.support().z != last.placed().z - last.support().z) {
                    weight *= 0.6;
                }
                score += weight;
            }
            previous = last;
            last = placement;
            if (++count >= 8 && score >= 8) {
                Match match = new Match(count, score);
                count = 1;
                score = 0;
                return match;
            }
            return null;
        }

        private boolean continuesBridge(Placement placement) {
            Vector3i support = placement.support();
            Vector3i placed = placement.placed();
            if (placed.y != support.y || Math.abs(placed.x - support.x) + Math.abs(placed.z - support.z) != 1
                    || placed.equals(last.placed()) || previous != null && placed.equals(previous.placed())) return false;
            return support.equals(last.placed()) || previous != null && support.equals(previous.placed());
        }

        void reset() {
            last = null;
            previous = null;
            score = 0;
            count = 0;
        }
    }
}
