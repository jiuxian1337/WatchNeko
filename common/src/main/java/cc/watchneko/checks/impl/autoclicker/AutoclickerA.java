package cc.watchneko.checks.impl.autoclicker;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.math.Statistics;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;

import java.util.ArrayList;
import java.util.List;

/**
 * Ported behaviour from MX-Project AutoClickerCheck.
 * <p>
 * This check looks for unnatural stability in swing delays (in ticks),
 * using per-window kurtosis and entropy jitter ("jiff delta") analysis.
 */
@CheckData(
        name = "AutoclickerA",
        description = "Detects stable, macro-like clicking patterns during combat"
)
public final class AutoclickerA extends Check implements PacketCheck {

    // MX-style defaults
    private static final int DEFAULT_MAX_DELAY_TICKS = 25;
    private static final long TELEPORT_GRACE_MS = 500L;
    private static final int DEFAULT_STACK_SIZE = 100;
    private static final int DEFAULT_WINDOW_SIZE = 20;
    private static final long DEFAULT_REQUIRE_MOVE_WITHIN_MS = 500L;
    private static final long DEFAULT_REQUIRE_ATTACK_WITHIN_MS = 7000L;
    private static final double DEFAULT_ENTROPY_JIFF_MIN = 0.04;
    private static final double DEFAULT_ENTROPY_JIFF_MAX = 0.06;
    private final List<Long> stack = new ArrayList<>(DEFAULT_STACK_SIZE + 10);
    // Config
    private boolean enabled = true;
    private int maxDelayTicks = DEFAULT_MAX_DELAY_TICKS;
    private int stackSize = DEFAULT_STACK_SIZE;
    private int windowSize = DEFAULT_WINDOW_SIZE;
    private long requireMoveWithinMs = DEFAULT_REQUIRE_MOVE_WITHIN_MS;
    private long requireAttackWithinMs = DEFAULT_REQUIRE_ATTACK_WITHIN_MS;
    private double entropyJiffMin = DEFAULT_ENTROPY_JIFF_MIN;
    private double entropyJiffMax = DEFAULT_ENTROPY_JIFF_MAX;
    // State
    private long oldTime = System.currentTimeMillis();
    private long lastMove = System.currentTimeMillis();
    private long lastAttack = System.currentTimeMillis();
    private boolean collectClicks;
    private boolean entropyQuery;

    public AutoclickerA(final PlayerData player) {
        super(player);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public void onReload(final ConfigManager config) {
        enabled = config.getBooleanElse(getConfigName() + ".enabled", true);
        maxDelayTicks = clamp(config.getIntElse(getConfigName() + ".max-delay-ticks", DEFAULT_MAX_DELAY_TICKS), 3, 60);
        stackSize = clamp(config.getIntElse(getConfigName() + ".stack-size", DEFAULT_STACK_SIZE), 20, 300);
        windowSize = clamp(config.getIntElse(getConfigName() + ".window-size", DEFAULT_WINDOW_SIZE), 5, 60);
        requireMoveWithinMs = clamp(config.getLongElse(getConfigName() + ".require-move-within-ms", DEFAULT_REQUIRE_MOVE_WITHIN_MS), 0L, 5000L);
        requireAttackWithinMs = clamp(config.getLongElse(getConfigName() + ".require-attack-within-ms", DEFAULT_REQUIRE_ATTACK_WITHIN_MS), 250L, 15000L);
        entropyJiffMin = config.getDoubleElse(getConfigName() + ".entropy-jiff-min", DEFAULT_ENTROPY_JIFF_MIN);
        entropyJiffMax = config.getDoubleElse(getConfigName() + ".entropy-jiff-max", DEFAULT_ENTROPY_JIFF_MAX);
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (!isEnabled() || !enabled) {
            return;
        }

        // Treat tick packets as "activity" for lastMove gating.
        if (isTickPacketIncludingNonMovement(event.getPacketType()) || event.getPacketType() == PacketType.Play.Client.VEHICLE_MOVE) {
            lastMove = System.currentTimeMillis();
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            final WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);
            final DiggingAction action = digging.getAction();
            if (action == DiggingAction.START_DIGGING || action == DiggingAction.FINISHED_DIGGING || action == DiggingAction.CANCELLED_DIGGING) {
                collectClicks = false;
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            final WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                lastAttack = System.currentTimeMillis();
                collectClicks = true;
            }
            return;
        }

        if (event.getPacketType() != PacketType.Play.Client.ANIMATION) {
            return;
        }

        // The reference implementation refuses to sample for 500 ms after a teleport, because the client
        // stops producing swing packets for a moment while it reconciles the new position and the gaps
        // that leaves look exactly like a macro's steady delay.
        if (System.currentTimeMillis() - player.lastServerTeleport < TELEPORT_GRACE_MS) {
            oldTime = System.currentTimeMillis();
            return;
        }

        final long now = System.currentTimeMillis();
        final long delayTicks = (now - oldTime) / 50L;
        oldTime = now;

        if (delayTicks <= 0L || delayTicks >= maxDelayTicks) {
            return;
        }

        if (!collectClicks) {
            return;
        }

        if (requireMoveWithinMs > 0 && lastMove + requireMoveWithinMs <= now) {
            return;
        }

        if (requireAttackWithinMs > 0 && lastAttack + requireAttackWithinMs <= now) {
            return;
        }

        stack.add(delayTicks);
        if (stack.size() > stackSize) {
            analyze();
        }
    }

    private void analyze() {
        final List<Double> kurtosisStack = new ArrayList<>();
        final List<Double> shannonStack = new ArrayList<>();
        final List<Double> localDelta = new ArrayList<>(windowSize);

        for (long delay : stack) {
            localDelta.add((double) delay);
            if (localDelta.size() >= windowSize) {
                // Statistics.getKurtosis is the reference implementation's own estimator. MathUtil's
                // two-argument overload computes m4/sigma^4 - 3, which is a different statistic and does
                // not compare against zero the same way.
                kurtosisStack.add(Statistics.getKurtosis(localDelta));
                shannonStack.add(Statistics.getShannonEntropy(localDelta));
                localDelta.clear();
            }
        }

        final double maxKurtosis = Statistics.getMax(kurtosisStack);
        if (maxKurtosis < 0.0) {
            if (flagAndAlert(String.format("t=mx-kurtosis max=%.3f n=%d", maxKurtosis, stack.size()))) {
                player.mitigateDamage();
                stack.clear();
                entropyQuery = false;
                return;
            }
        }

        final List<Float> jiff = Statistics.getJiffDelta(shannonStack, 2);
        if (!jiff.isEmpty()) {
            final double min = Statistics.getMin(jiff);
            final double max = Statistics.getMax(jiff);

            if (min < entropyJiffMin && max < entropyJiffMax) {
                if (!entropyQuery) {
                    entropyQuery = true;
                } else {
                    if (flagAndAlert(String.format("t=mx-entropy min=%.3f max=%.3f n=%d", min, max, stack.size()))) {
                        player.mitigateDamage();
                        entropyQuery = false;
                    }
                }
            } else {
                entropyQuery = false;
            }
        } else {
            entropyQuery = false;
        }

        stack.clear();
    }
}
