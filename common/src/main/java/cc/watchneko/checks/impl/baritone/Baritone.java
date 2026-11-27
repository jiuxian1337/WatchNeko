package cc.watchneko.checks.impl.baritone;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.BlockBreak;
import cc.watchneko.utils.anticheat.update.BlockPlace;
import cc.watchneko.utils.math.GrimMath;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.util.Vector3i;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// Implements the mining and placement aim rules from BaritoneRemover, commit 4ddb1f9.
@CheckData(name = "Baritone", description = "Repeated automated mining and placement aim patterns", setback = -1)
public final class Baritone extends Check {
    private static final double MAX_MINING_MOVEMENT_SQUARED = 0.05 * 0.05;
    private volatile Settings config;
    private final Window approachWindow = new Window(10, 12, 60, 8);
    private final Window placementWindow = new Window(8, 8, 45, 0);
    private final Window traverseWindow = new Window(4, 4, 90, 0);
    private final Window holdWindow = new Window(12, 15, 90, 8);
    private final Placements placements = new Placements();

    private UUID world;
    private long lastTeleport;
    private Settings appliedSettings;
    private boolean havePosition;
    private Look previousLook;
    private Look currentLook;
    private MiningSession mining;

    public Baritone(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager configManager) {
        config = new Settings(configManager.getBooleanElse("Baritone.enabled", true));
    }

    public boolean isConfiguredEnabled() {
        return config.enabled();
    }

    public void onMovement(boolean hasPosition, boolean hasLook) {
        if (!updateContext() || player.packetStateData.lastPacketWasOnePointSeventeenDuplicate) {
            return;
        }
        havePosition |= hasPosition;
        if (!hasLook || !havePosition) {
            return;
        }
        double eyeHeight = eyeHeight();
        if (!Double.isFinite(eyeHeight) || !Float.isFinite(player.xRot)
                || !Float.isFinite(player.yRot) || Math.abs(player.yRot) > 90) {
            clearLookAndMining();
            return;
        }
        long now = System.nanoTime();
        previousLook = currentLook;
        currentLook = new Look(player.x, player.y, player.z, eyeHeight, player.xRot, player.yRot, now);
        if (mining != null) {
            if (mining.expired(now)) {
                mining = null;
            } else {
                mining.add(currentLook);
            }
        }
    }

    public void onDig(BlockBreak block, boolean accepted) {
        if (!updateContext()) {
            return;
        }
        if (!accepted) {
            mining = null;
            return;
        }
        long now = System.nanoTime();
        switch (block.action) {
            case START_DIGGING -> {
                mining = new MiningSession(block.position, now);
                checkMiningAim(block, now);
            }
            case CANCELLED_DIGGING -> {
                if (mining != null && mining.isTarget(block.position)) {
                    mining = null;
                }
            }
            case FINISHED_DIGGING -> {
                MiningSession session = mining;
                mining = null;
                if (session != null && session.matches(block.position, now)) {
                    report("C", traverseWindow.add(block.position, 0, now));
                }
            }
            default -> {
            }
        }
    }

    private void checkMiningAim(BlockBreak block, long now) {
        Aim aim = null;
        if (fresh(previousLook, currentLook, now, 300, 200)
                && stableMiningPosition(previousLook, currentLook, player.x, player.y, player.z, eyeHeight())) {
            aim = miningAim(previousLook, currentLook, block.position, block.face);
        }
        boolean matchedApproach = aim != null && now - currentLook.nanos() <= TimeUnit.MILLISECONDS.toNanos(250)
                && aim.miningApproach();
        boolean matchedHold = aim != null && aim.miningHold();
        report("A", approachWindow.add(block.position, matchedApproach ? aim.expectedPitch() : Double.NaN, now));
        report("D", holdWindow.add(block.position, matchedHold ? aim.expectedPitch() : Double.NaN, now));
    }

    public void onPlaceRequest(BlockPlace block) {
        if (!updateContext() || !block.isBlock) {
            return;
        }
        BlockFace face = block.getFace();
        if (!validFace(face)) {
            return;
        }
        long now = System.nanoTime();
        Aim match = null;
        double eyeHeight = eyeHeight();
        if (fresh(previousLook, currentLook, now, 150, 500) && currentLook.eyeHeight() == eyeHeight) {
            Look actionLook = new Look(player.x, player.y, player.z, eyeHeight,
                    currentLook.yaw(), currentLook.pitch(), currentLook.nanos());
            Aim aim = placementAim(previousLook, actionLook, block.position, face);
            if (aim.placementSnap()) {
                match = aim;
            }
        }
        Vector3i placed = new Vector3i(block.position.x + face.getModX(), block.position.y + face.getModY(),
                block.position.z + face.getModZ());
        placements.add(block.position, placed, match, now);
    }

    public void onPlaceConfirmed(UUID eventWorld, Vector3i support, Vector3i placed, long now) {
        if (!updateContext() || !world.equals(eventWorld)) {
            return;
        }
        Aim match = placements.confirm(support, placed, now);
        if (match != null) {
            report("B", placementWindow.add(support, 0, System.nanoTime()));
        }
    }

    private boolean updateContext() {
        Settings current = config;
        if (!current.enabled() || !isEnabled() || isExemptPermission() || player.disableGrim
                || player.inVehicle() || player.isFlying || player.isGliding || player.isInBed
                || player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR
                || player.packetStateData.lastPacketWasTeleport || player.packetStateData.horseInteractCausedForcedRotation
                || player.getSetbackTeleportUtil().shouldBlockMovement()) {
            if (world != null) {
                clear();
                world = null;
            }
            return false;
        }
        UUID currentWorld = player.getWorldUID();
        if (currentWorld == null || !currentWorld.equals(world) || lastTeleport != player.lastServerTeleport || current != appliedSettings) {
            clear();
            world = currentWorld;
            lastTeleport = player.lastServerTeleport;
            appliedSettings = current;
        }
        return world != null;
    }

    private double eyeHeight() {
        double[] heights = player.getPossibleEyeHeights();
        return heights == null || heights.length == 0 ? Double.NaN : heights[0];
    }

    private void clear() {
        havePosition = false;
        clearLookAndMining();
        approachWindow.clear();
        placementWindow.clear();
        traverseWindow.clear();
        holdWindow.clear();
        placements.clear();
    }

    private void clearLookAndMining() {
        previousLook = null;
        currentLook = null;
        mining = null;
    }

    private void report(String pattern, Batch batch) {
        if (batch == null) {
            return;
        }
        String verbose = "pattern=" + pattern + ", targets=" + batch.matches() + "/" + batch.samples();
        if (batch.pitchSpread() > 0) {
            verbose += String.format(Locale.ROOT, ", pitch-spread=%.2f", batch.pitchSpread());
        }
        flagAndAlert(verbose);
    }

    private record Settings(boolean enabled) {
    }

    record Look(double x, double y, double z, double eyeHeight, float yaw, float pitch, long nanos) {
        double distanceSquared(double otherX, double otherY, double otherZ) {
            double dx = x - otherX;
            double dy = y - otherY;
            double dz = z - otherZ;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    record Aim(double yawError, double pitchError, double previousYawError, double previousPitchError,
               double distance, double turn, double expectedPitch) {
        private boolean inRange() {
            return distance >= 1.0 && distance <= 5.25;
        }

        private boolean preciseMiningAim() {
            return inRange() && yawError <= 1.2 && pitchError <= 0.12;
        }

        boolean miningApproach() {
            return preciseMiningAim() && Math.hypot(previousYawError, previousPitchError) >= 3.0 && turn >= 3.0;
        }

        boolean miningHold() {
            return preciseMiningAim() && previousYawError <= 1.2 && previousPitchError <= 0.12;
        }

        boolean placementSnap() {
            return inRange() && Math.hypot(yawError, pitchError) <= 0.8
                    && Math.hypot(previousYawError, previousPitchError) >= 2.25 && turn >= 2.0;
        }
    }

    static boolean fresh(Look previous, Look current, long now, long maxActionMillis, long maxTurnMillis) {
        return previous != null && current != null && now - current.nanos() >= 0
                && now - current.nanos() <= TimeUnit.MILLISECONDS.toNanos(maxActionMillis)
                && current.nanos() - previous.nanos() >= 0
                && current.nanos() - previous.nanos() <= TimeUnit.MILLISECONDS.toNanos(maxTurnMillis)
                && previous.eyeHeight() == current.eyeHeight();
    }

    static boolean stableMiningPosition(Look previous, Look current, double x, double y, double z, double eyeHeight) {
        return current.eyeHeight() == eyeHeight
                && current.distanceSquared(previous.x(), previous.y(), previous.z()) <= MAX_MINING_MOVEMENT_SQUARED
                && current.distanceSquared(x, y, z) <= MAX_MINING_MOVEMENT_SQUARED;
    }

    static Aim miningAim(Look previous, Look current, Vector3i target, BlockFace face) {
        double x = target.x + 0.5;
        double y = target.y + 0.5;
        double z = target.z + 0.5;
        Aim center = aimAt(previous, current, x, y, z);
        if (!validFace(face)) {
            return center;
        }
        Aim side = aimAt(previous, current, x + face.getModX() * 0.5,
                y + face.getModY() * 0.5, z + face.getModZ() * 0.5);
        return Math.hypot(side.yawError(), side.pitchError()) < Math.hypot(center.yawError(), center.pitchError())
                ? side : center;
    }

    static Aim placementAim(Look previous, Look current, Vector3i support, BlockFace face) {
        return aimAt(previous, current, support.x + 0.5 + face.getModX() * 0.5,
                support.y + 0.25 + face.getModY() * 0.5, support.z + 0.5 + face.getModZ() * 0.5);
    }

    static boolean validFace(BlockFace face) {
        return face != null && face != BlockFace.OTHER
                && Math.abs(face.getModX()) + Math.abs(face.getModY()) + Math.abs(face.getModZ()) == 1;
    }

    private static Aim aimAt(Look previous, Look current, double targetX, double targetY, double targetZ) {
        Angles before = anglesTo(previous, targetX, targetY, targetZ);
        Angles now = anglesTo(current, targetX, targetY, targetZ);
        return new Aim(yawDistance(current.yaw(), now.yaw()), Math.abs(current.pitch() - now.pitch()),
                yawDistance(previous.yaw(), before.yaw()), Math.abs(previous.pitch() - before.pitch()),
                now.distance(), yawDistance(previous.yaw(), current.yaw()) + Math.abs(previous.pitch() - current.pitch()),
                now.pitch());
    }

    private static Angles anglesTo(Look frame, double targetX, double targetY, double targetZ) {
        double x = targetX - frame.x();
        double y = frame.y() + frame.eyeHeight() - targetY;
        double z = targetZ - frame.z();
        double horizontal = Math.hypot(x, z);
        return new Angles((float) Math.toDegrees(Math.atan2(-x, z)),
                (float) Math.toDegrees(Math.atan2(y, horizontal)), Math.hypot(horizontal, y));
    }

    private static double yawDistance(float first, float second) {
        return Math.abs(GrimMath.wrapAngleTo180(first - second));
    }

    private record Angles(float yaw, float pitch, double distance) {
    }

    static final class Window {
        private final Map<Vector3i, Sample> samples = new LinkedHashMap<>();
        private final int required;
        private final int capacity;
        private final long maxAge;
        private final double minimumPitchSpread;

        Window(int required, int capacity, long seconds, double minimumPitchSpread) {
            this.required = required;
            this.capacity = capacity;
            this.maxAge = TimeUnit.SECONDS.toNanos(seconds);
            this.minimumPitchSpread = minimumPitchSpread;
        }

        Batch add(Vector3i target, double pitch, long now) {
            samples.values().removeIf(sample -> now - sample.nanos() > maxAge);
            if (samples.containsKey(target)) {
                return null;
            }
            samples.put(target, new Sample(pitch, now));
            if (samples.size() > capacity) {
                samples.remove(samples.keySet().iterator().next());
            }
            if (samples.size() < required) {
                return null;
            }
            int matches = 0;
            double lowest = Double.POSITIVE_INFINITY;
            double highest = Double.NEGATIVE_INFINITY;
            for (Sample sample : samples.values()) {
                if (Double.isFinite(sample.pitch())) {
                    matches++;
                    lowest = Math.min(lowest, sample.pitch());
                    highest = Math.max(highest, sample.pitch());
                }
            }
            double spread = highest - lowest;
            if (matches < required || spread < minimumPitchSpread) {
                return null;
            }
            Batch batch = new Batch(matches, samples.size(), spread);
            clear();
            return batch;
        }

        void clear() {
            samples.clear();
        }
    }

    record Batch(int matches, int samples, double pitchSpread) {
    }

    private record Sample(double pitch, long nanos) {
    }

    static final class Placements {
        private static final long CONFIRMATION_WINDOW = TimeUnit.SECONDS.toNanos(1);
        private static final int MAX_PENDING = 16;
        private final Deque<Placement> pending = new ArrayDeque<>();

        void add(Vector3i support, Vector3i placed, Aim aim, long now) {
            expire(now);
            if (pending.size() == MAX_PENDING) {
                pending.removeFirst();
            }
            pending.addLast(new Placement(support, placed, aim, now));
        }

        Aim confirm(Vector3i support, Vector3i placed, long now) {
            expire(now);
            Placement match = null;
            boolean ambiguous = false;
            for (Iterator<Placement> it = pending.iterator(); it.hasNext(); ) {
                Placement candidate = it.next();
                if (now - candidate.nanos() >= 0 && candidate.support().equals(support) && candidate.placed().equals(placed)) {
                    it.remove();
                    ambiguous |= match != null;
                    match = candidate;
                }
            }
            return match == null || ambiguous ? null : match.aim();
        }

        private void expire(long now) {
            while (!pending.isEmpty() && now - pending.getFirst().nanos() > CONFIRMATION_WINDOW) {
                pending.removeFirst();
            }
        }

        void clear() {
            pending.clear();
        }
    }

    private record Placement(Vector3i support, Vector3i placed, Aim aim, long nanos) {
    }

    static final class MiningSession {
        private final Vector3i target;
        private final long started;
        private Look previous;
        private int count;
        private double yawTravel;
        private double pitchTravel;
        private double totalPitch;
        private float minimumPitch = Float.POSITIVE_INFINITY;
        private float maximumPitch = Float.NEGATIVE_INFINITY;

        MiningSession(Vector3i target, long started) {
            this.target = target;
            this.started = started;
        }

        boolean isTarget(Vector3i position) {
            return target.equals(position);
        }

        boolean expired(long now) {
            return now - started > TimeUnit.SECONDS.toNanos(10);
        }

        void add(Look frame) {
            if (previous != null) {
                yawTravel += yawDistance(frame.yaw(), previous.yaw());
                pitchTravel += Math.abs(frame.pitch() - previous.pitch());
            }
            previous = frame;
            count++;
            totalPitch += frame.pitch();
            minimumPitch = Math.min(minimumPitch, frame.pitch());
            maximumPitch = Math.max(maximumPitch, frame.pitch());
        }

        boolean matches(Vector3i position, long now) {
            return isTarget(position) && !expired(now) && now - started >= TimeUnit.MILLISECONDS.toNanos(150)
                    && count >= 10 && yawTravel >= 4.0 && pitchTravel <= 0.02 && maximumPitch - minimumPitch <= 0.01
                    && Math.abs(totalPitch / count - 26.0) <= 0.75;
        }
    }
}
