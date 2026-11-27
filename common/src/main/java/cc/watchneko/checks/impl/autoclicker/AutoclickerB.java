package cc.watchneko.checks.impl.autoclicker;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

@CheckData(name = "AutoclickerB", alternativeName = "AutoclickerF", description = "Repeated ordered combat swing intervals", experimental = true, setback = -1)
public final class AutoclickerB extends Check implements PacketCheck {
    private static final long TELEPORT_GRACE_MS = 500;
    private static final long MIN_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(2);

    private volatile Settings settings;
    private Settings activeSettings;
    private ClickWindows windows;
    private boolean inCombat;
    private long lastAttack;
    private long teleportTime;

    public AutoclickerB(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        double correlation = config.getDoubleElse("AutoclickerB.min-correlation", 0.95);
        double relativeError = config.getDoubleElse("AutoclickerB.max-relative-error", 0.10);
        double minimumDeviation = config.getDoubleElse("AutoclickerB.min-deviation-ms", 4.0);
        settings = new Settings(
                config.getBooleanElse("AutoclickerB.enabled", false),
                Math.max(16, Math.min(96, config.getIntElse("AutoclickerB.window-size", 24))),
                Math.max(1, Math.min(10, config.getIntElse("AutoclickerB.required-matches", 2))),
                TimeUnit.MILLISECONDS.toNanos(Math.max(50, Math.min(1500, config.getIntElse("AutoclickerB.max-gap-ms", 350)))),
                TimeUnit.MILLISECONDS.toNanos(Math.max(250, Math.min(10_000, config.getIntElse("AutoclickerB.combat-window-ms", 3000)))),
                correlation > 0 && correlation <= 1 ? correlation : 0.95,
                relativeError > 0 && relativeError < 1 ? relativeError : 0.10,
                minimumDeviation > 0 && minimumDeviation <= 100 ? minimumDeviation * 1_000_000 : 4_000_000
        );
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Settings current = settings;
        if (!current.enabled || !isEnabled() || !player.isExperimentalChecks() || player.disableGrim || isExemptPermission()) {
            endCombat();
            return;
        }
        if (activeSettings != current) {
            endCombat();
            activeSettings = current;
            windows = new ClickWindows(current);
        }
        if (teleportTime != player.lastServerTeleport) {
            teleportTime = player.lastServerTeleport;
            endCombat();
        }

        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            DiggingAction action = new WrapperPlayClientPlayerDigging(event).getAction();
            if (action == DiggingAction.START_DIGGING || action == DiggingAction.FINISHED_DIGGING
                    || action == DiggingAction.CANCELLED_DIGGING) {
                endCombat();
            }
            return;
        }
        if (type != PacketType.Play.Client.INTERACT_ENTITY && type != PacketType.Play.Client.ANIMATION) {
            return;
        }
        if (event.isCancelled() || !player.isTickingReliablyFor(3)
                || System.currentTimeMillis() - teleportTime < TELEPORT_GRACE_MS) {
            endCombat();
            return;
        }

        long now = System.nanoTime();
        if (inCombat && now - lastAttack > current.combatWindowNanos) {
            endCombat();
        }
        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            if (new WrapperPlayClientInteractEntity(event).getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                inCombat = true;
                lastAttack = now;
            }
            return;
        }
        if (!inCombat) {
            return;
        }
        if (new WrapperPlayClientAnimation(event).getHand() != InteractionHand.MAIN_HAND) {
            windows.reset();
            return;
        }

        Match match = windows.add(now);
        if (match != null) {
            flagAndAlert(String.format(Locale.ROOT, "corr=%.3f, error=%.3f, intervals=%d",
                    match.correlation, match.relativeError, current.windowSize * 2));
        }
    }

    private void endCombat() {
        inCombat = false;
        if (windows != null) {
            windows.reset();
        }
    }

    record Settings(boolean enabled, int windowSize, int requiredMatches, long maxGapNanos, long combatWindowNanos,
                    double minCorrelation, double maxRelativeError, double minDeviationNanos) {
    }

    record Match(double correlation, double relativeError) {
    }

    static final class ClickWindows {
        private final Settings settings;
        private final double[] first;
        private final double[] second;
        private int samples;
        private int consecutiveMatches;
        private boolean anchored;
        private long previousTime;

        ClickWindows(Settings settings) {
            this.settings = settings;
            first = new double[settings.windowSize];
            second = new double[settings.windowSize];
        }

        Match add(long now) {
            long interval = now - previousTime;
            previousTime = now;
            // Coalesced arrivals cannot establish the time between client swings.
            if (!anchored || interval < MIN_INTERVAL_NANOS || interval > settings.maxGapNanos) {
                reset();
                anchored = true;
                return null;
            }
            if (samples < first.length) {
                first[samples] = interval;
            } else {
                second[samples - first.length] = interval;
            }
            samples++;
            if (samples < first.length + second.length) {
                return null;
            }

            samples = 0;
            Match match = compare(first, second, settings);
            if (match == null) {
                consecutiveMatches = 0;
                return null;
            }
            if (++consecutiveMatches >= settings.requiredMatches) {
                consecutiveMatches = 0;
                return match;
            }
            return null;
        }

        void reset() {
            samples = 0;
            consecutiveMatches = 0;
            anchored = false;
        }
    }

    static Match compare(double[] first, double[] second, Settings settings) {
        double firstSum = 0;
        double secondSum = 0;
        double absoluteError = 0;
        for (int i = 0; i < first.length; i++) {
            firstSum += first[i];
            secondSum += second[i];
            absoluteError += Math.abs(first[i] - second[i]);
        }
        double error = absoluteError / ((firstSum + secondSum) * 0.5);
        if (error > settings.maxRelativeError) {
            return null;
        }

        double firstMean = firstSum / first.length;
        double secondMean = secondSum / second.length;
        double firstSquares = 0;
        double secondSquares = 0;
        double products = 0;
        for (int i = 0; i < first.length; i++) {
            double a = first[i] - firstMean;
            double b = second[i] - secondMean;
            firstSquares += a * a;
            secondSquares += b * b;
            products += a * b;
        }
        double deviationFloor = first.length * settings.minDeviationNanos * settings.minDeviationNanos;
        if (firstSquares < deviationFloor || secondSquares < deviationFloor) {
            return null;
        }
        double correlation = products / Math.sqrt(firstSquares * secondSquares);
        if (correlation < settings.minCorrelation || followsShortPeriod(first, firstMean, firstSquares)
                || followsShortPeriod(second, secondMean, secondSquares)) {
            return null;
        }
        return new Match(Math.min(correlation, 1.0), error);
    }

    private static boolean followsShortPeriod(double[] intervals, double mean, double totalSquares) {
        // A shared short cadence can make two windows look identical without a distinctive sequence.
        for (int period = 2; period <= Math.min(8, intervals.length / 3); period++) {
            double explained = 0;
            for (int phase = 0; phase < period; phase++) {
                double sum = 0;
                int count = 0;
                for (int i = phase; i < intervals.length; i += period) {
                    sum += intervals[i] - mean;
                    count++;
                }
                explained += sum * sum / count;
            }
            if (explained >= totalSquares * 0.8) {
                return true;
            }
        }
        return false;
    }
}
