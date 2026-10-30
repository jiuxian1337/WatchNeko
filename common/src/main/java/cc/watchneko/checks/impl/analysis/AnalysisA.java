package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.analysis.RotationWindow;
import cc.watchneko.analysis.VerdictVotes;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.cloud.CloudContractException;
import cc.watchneko.cloud.CloudGateway;
import cc.watchneko.cloud.FeatureSample;
import cc.watchneko.cloud.RecordingSession;
import cc.watchneko.cloud.RecordingSessions;
import cc.watchneko.cloud.SampleIdentity;
import cc.watchneko.cloud.Verdict;
import cc.watchneko.cloud.VerdictLabel;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.LogUtil;
import cc.watchneko.utils.anticheat.update.AttackAnchor;
import cc.watchneko.utils.anticheat.update.AttackSignalListener;
import cc.watchneko.utils.anticheat.update.RotationUpdate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Cloud-assisted rotation detection: the plugin samples, the cloud classifies, the plugin decides.
 *
 * <p>The division of authority is the point of this check. The cloud answers per sample with a verdict and
 * a score; those answers only ever become <em>votes</em>. Whether a run of votes amounts to a violation is
 * decided locally, by {@link VerdictVotes}, against thresholds that belong to the server owner. The cloud
 * never punishes and never gets to skip the local rule.
 *
 * <p>The packet thread does three cheap things here: append a rotation update, collect any sample whose
 * window just completed, and drain verdicts that came back. It never waits on the network — a batch is
 * handed to an executor and at most one is outstanding at a time.
 */
@CheckData(
        name = "AnalysisA",
        configName = "AnalysisA",
        description = "cloud-assisted rotation detection",
        decay = 0.05
)
public final class AnalysisA extends Check implements RotationCheck, AttackSignalListener {

    private final RotationWindow window = new RotationWindow();
    private final ArrayDeque<Long> pendingAnchors = new ArrayDeque<>();
    private final List<FeatureSample> batch = new ArrayList<>();
    private final ConcurrentLinkedQueue<VerdictLabel> incomingVerdicts = new ConcurrentLinkedQueue<>();

    private VerdictVotes votes = new VerdictVotes(10, 8);
    private boolean inFlight;
    private boolean debugLog;
    private boolean quotaGated;
    private long quotaGateUntilMs;
    private double gateMinEnergy = 1.25;
    private long quotaCooldownMs = 30_000L;
    private int batchSize = 8;
    private long sampleCounter;
    private long mitigateDamageMs = 5000L;

    public AnalysisA(final PlayerData player) {
        super(player);
        player.rotationSequence.addListener(this);
    }

    @Override
    public void onReload(final ConfigManager config) {
        debugLog = config.getBooleanElse("AnalysisA.debug-log", false);
        batchSize = Math.max(1, config.getIntElse("AnalysisA.batch-size", 8));
        gateMinEnergy = Math.max(0.0, config.getDoubleElse("AnalysisA.local-gate-min-energy", 1.25));
        quotaCooldownMs = Math.max(1L, config.getIntElse("AnalysisA.quota-cooldown-ms", 30_000));
        mitigateDamageMs = Math.max(0L, (long) config.getDoubleElse("AnalysisA.mitigate-damage-ms", mitigateDamageMs));

        final int windowSize = Math.max(1, config.getIntElse("AnalysisA.analysis-window-size", 10));
        final int minCheatVotes = Math.max(1, config.getIntElse("AnalysisA.min-cheat-votes", 8));
        votes = new VerdictVotes(windowSize, minCheatVotes);

        // Check's constructor calls reload() from inside super(), before this class's field
        // initializers have run, so on that first pass every collection below is still null and
        // there is no state to drop anyway. They are all created in the same initializer block,
        // so testing one is enough.
        if (window != null) {
            window.clear();
            pendingAnchors.clear();
            batch.clear();
            incomingVerdicts.clear();
        }
    }

    @Override
    public void onAttack(final AttackAnchor anchor) {
        pendingAnchors.addLast(anchor.rotationSequence());
    }

    @Override
    public void process(final RotationUpdate update) {
        if (!shouldModifyPackets()) {
            return;
        }

        final CloudGateway gateway = CloudGateway.current();
        if (gateway == null) {
            return;
        }

        if (quotaGated && System.currentTimeMillis() >= quotaGateUntilMs) {
            // Try full throughput again; the cloud answers 429 again if there is still no room.
            quotaGated = false;
        }

        window.append(player.rotationSequence.position(), update.getDeltaXRot(), update.getDeltaYRot());
        collectCompletedSamples(gateway);
        drainVerdicts();
        flushIfReady(gateway);
    }

    private void collectCompletedSamples(final CloudGateway gateway) {
        while (!pendingAnchors.isEmpty()) {
            final long center = pendingAnchors.peekFirst();
            final Optional<RotationWindow.ExtractedWindow> extracted = window.extract(center);
            if (extracted.isEmpty()) {
                return;
            }

            pendingAnchors.removeFirst();

            // Under quota pressure only the samples that actually moved get sent. A window with no
            // movement says nothing about aim, so dropping it loses no detection power - and unlike
            // dropping arbitrary samples, it does not bias what the cloud sees.
            if (gateway.canInfer() && quotaGated && extracted.get().energy() < gateMinEnergy) {
                debug("dropped a still window while quota-gated (energy=" + extracted.get().energy() + ")");
                continue;
            }

            // The player name travels in plain text: it is what lets an operator find this sample later
            // and undo the upload it came from. It is also what the sample id is built from.
            final String playerName = player.getName();
            final double[] features = extracted.get().features();

            // A player being recorded is producing training data, not evidence. The sample goes to the
            // session and is not sent for inference: a recording session must never influence the
            // detection it is meant to help train.
            //
            // A closed session is one the admin already stopped whose upload has not succeeded yet. It
            // stays in the registry so the retry can find it, but it is no longer recording: samples for
            // that player must go back to being inferred rather than silently disappearing into a set
            // that will not grow.
            final Optional<RecordingSession> recording = RecordingSessions.active(player.uuid);
            if (recording.isPresent() && !recording.get().isClosed()) {
                // Recording samples are stored by the cloud under their id, so the id has to be unique
                // across every session this tenant uploads - not just within this connection. Scoping it
                // to the session is what makes a reconnect, which resets the per-player counter, stop
                // producing ids the cloud already has.
                recording.get().add(new FeatureSample(
                        SampleIdentity.forRecording(playerName, recording.get().sessionId(), ++sampleCounter),
                        playerName,
                        features));
                continue;
            }

            // Nothing published to classify with yet. The sample would sit in the batch until it grew
            // past the limit and was dropped anyway; not accumulating it keeps the memory flat while the
            // server is recording its first dataset.
            if (!gateway.canInfer()) {
                continue;
            }

            batch.add(new FeatureSample(
                    SampleIdentity.of(playerName, ++sampleCounter), playerName, features));
        }
    }

    private void flushIfReady(final CloudGateway gateway) {
        if (inFlight || batch.size() < batchSize) {
            return;
        }

        final List<FeatureSample> outgoing = List.copyOf(batch);
        batch.clear();
        inFlight = true;

        gateway.inferAsync(outgoing).whenComplete((result, error) -> {
            inFlight = false;
            if (error != null) {
                if (error.getCause() instanceof CloudContractException contract
                        && contract.status() == 429) {
                    enterQuotaGate();
                }
                debug("cloud call failed, dropping " + outgoing.size() + " samples: " + error.getMessage());
                return;
            }
            for (final Verdict verdict : result.verdicts()) {
                incomingVerdicts.add(verdict.verdict());
            }
        });
    }

    private void drainVerdicts() {
        VerdictLabel label;
        boolean added = false;
        while ((label = incomingVerdicts.poll()) != null) {
            votes.add(label);
            added = true;
        }

        if (!added || !votes.shouldFlag()) {
            if (added) {
                reward();
            }
            return;
        }

        if (flagAndAlert("cloud votes=" + votes.cheatVotes() + "/" + votes.size())) {
            votes.clear();
            player.mitigateDamage(mitigateDamageMs);
        }
    }

    /**
     * Over quota: stop sending everything and send only what still carries signal.
     *
     * <p>A 429 is not an outage, so it must not disable detection; it is an instruction to send less. The
     * gate reopens on a timer rather than on a signal, because the service does not tell us when capacity
     * returns — the cheapest correct answer is to try again and be told.
     */
    private void enterQuotaGate() {
        quotaGated = true;
        quotaGateUntilMs = System.currentTimeMillis() + quotaCooldownMs;
    }

    private void debug(final String message) {
        if (!debugLog) {
            return;
        }
        LogUtil.info("[AnalysisA] " + player.getName() + " " + message);
    }
}
