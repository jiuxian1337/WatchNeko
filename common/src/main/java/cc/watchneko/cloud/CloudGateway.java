package cc.watchneko.cloud;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.analysis.RotationWindow;
import cc.watchneko.utils.anticheat.LogUtil;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Everything the plugin knows about the cloud, shared by every player's check.
 *
 * <p>Rebuilt on config reload and published as a whole through a single volatile field, so a check either
 * sees a fully usable gateway or none at all — never a half-initialised one. That is the deliberate answer
 * to the failure mode that killed the previous implementation, where a static reference library was
 * dereferenced before anything had loaded it and every packet threw.
 *
 * <p><b>{@link #current()} returning {@code null} is a normal state</b>, not an error. It covers every way
 * the cloud can be unusable for either purpose: not enabled, not configured, unreachable, or answering
 * with an authentication failure. A check that sees {@code null} simply does nothing — no samples leave,
 * no verdicts arrive, no violation is raised.
 *
 * <p>A gateway is not the same thing as a usable model. Recording and uploading samples needs only an
 * authenticated, reachable cloud, so a gateway exists during the cold start — before any model has been
 * published. {@link #canInfer()} is what separates the two: it is false while there is no model, or while
 * the published model's feature width does not match this build. Detection is off in that state, but
 * {@code /watchneko record} works, which is how the first model gets trained at all.
 *
 * <p>When the cloud comes back, detection comes back on its own: {@link #recoverIfNeeded()} re-attempts on
 * a timer, including the upgrade from "reachable but no model" to "model published", so a server that
 * started before the cloud did — or before the first model existed — does not need a restart.
 */
public final class CloudGateway {

    private static final ExecutorService EXECUTOR =
            Executors.newFixedThreadPool(2, daemonThreads("watchneko-cloud"));

    private static final ScheduledExecutorService RECOVERY =
            Executors.newSingleThreadScheduledExecutor(daemonThreads("watchneko-cloud-recovery"));

    /** How long to wait before re-attempting a cloud that was not usable last time. */
    static final long RECOVERY_INTERVAL_SECONDS = 30L;

    private static volatile CloudGateway current;
    private static volatile ConfigManager lastConfig;
    private static volatile boolean recoveryScheduled;

    private final CloudClient client;
    private final ModelMetadata metadata;

    private CloudGateway(final CloudClient client, final ModelMetadata metadata) {
        this.client = client;
        this.metadata = metadata;
    }

    /** The gateway in effect, or {@code null} when cloud detection is not usable right now. */
    public static CloudGateway current() {
        return current;
    }

    /**
     * Whether this gateway can classify samples.
     *
     * <p>False during the cold start — the cloud is reachable and authenticated, but no model has been
     * published yet — and false when the published model expects a feature width this build does not
     * send. Both are states in which recording still works: a server that cannot infer is exactly the
     * server that needs to produce training data.
     */
    public boolean canInfer() {
        return metadata != null;
    }

    /** Rebuilds the gateway from config. Called from the async reload path, so blocking here is acceptable. */
    public static void reload(final ConfigManager config) {
        lastConfig = config;
        current = attemptFrom(config);
        if (current != null && !current.canInfer()) {
            // Said once per reload, not once per recovery tick: the cold start is a normal state, and
            // repeating it every 30 seconds would train operators to ignore the log.
            log(() -> LogUtil.info("AnalysisA cloud is reachable but has no usable model yet;"
                    + " recording is available, detection stays disabled"));
        }
        scheduleRecovery();
    }

    /**
     * Builds a gateway against an already-constructed transport, or returns empty for every reason the
     * cloud cannot be used at all. Never throws: a cloud that misbehaves must not take detection — or the
     * plugin — down with it.
     *
     * <p>A cloud that answers {@code 404 no_model} is <em>not</em> one of those reasons. That answer proves
     * the endpoint is reachable and the credentials are accepted, which is everything uploading samples
     * needs; the resulting gateway simply cannot infer yet. Treating it as unusable would deadlock the
     * first model: no gateway means no recording, and no recording means nothing to train a model from.
     *
     * <p>This is the seam the fail-open behaviour is tested through.
     */
    public static Optional<CloudGateway> attempt(
            final CloudTransport transport, final CloudSettings settings) {
        try {
            final CloudClient client = new CloudClient(transport, settings);

            final Optional<ModelMetadata> metadata = client.fetchModelMetadata();
            if (metadata.isEmpty()) {
                return Optional.of(new CloudGateway(client, null));
            }

            final int expected = RotationWindow.FEATURE_DIMENSIONS;
            if (metadata.get().featureDimensions() != expected) {
                // The model exists but this build cannot speak to it. Keep the gateway — uploading is
                // still how a correctly-shaped model gets trained — but never infer against it.
                log(() -> LogUtil.error("AnalysisA cloud model expects " + metadata.get().featureDimensions()
                        + " feature dimensions but this build sends " + expected
                        + "; detection stays disabled, recording still works"));
                return Optional.of(new CloudGateway(client, null));
            }

            return Optional.of(new CloudGateway(client, metadata.get()));
        } catch (Exception exception) {
            log(() -> LogUtil.error("AnalysisA could not reach the cloud; detection stays disabled", exception));
            return Optional.empty();
        }
    }

    /**
     * Re-attempts a cloud that was unusable last time, and upgrades a gateway that had no model once one
     * is published. Does nothing when there is no config yet or when detection is switched off.
     */
    static void recoverIfNeeded() {
        final ConfigManager config = lastConfig;
        if (config == null) {
            return;
        }
        if (!config.getBooleanElse("AnalysisA.cloud.enabled", false)) {
            return;
        }

        final CloudGateway existing = current;
        if (existing != null && existing.canInfer()) {
            return;
        }

        final CloudGateway recovered = attemptFrom(config);
        if (recovered == null) {
            return;
        }
        if (existing == null) {
            current = recovered;
            log(() -> LogUtil.info("AnalysisA cloud became available again; detection re-enabled"));
        } else if (recovered.canInfer()) {
            // The cold start ended: a model was published since the last attempt.
            current = recovered;
            log(() -> LogUtil.info("AnalysisA cloud now has a published model; detection enabled"));
        }
    }

    private static CloudGateway attemptFrom(final ConfigManager config) {
        if (!config.getBooleanElse("AnalysisA.cloud.enabled", false)) {
            return null;
        }

        final String endpoint = config.getStringElse("AnalysisA.cloud.endpoint", "");
        final String apiKey = config.getStringElse("AnalysisA.cloud.api-key", "");
        final String serverId = config.getStringElse("AnalysisA.cloud.server-id", "");
        final int timeoutMs = config.getIntElse("AnalysisA.cloud.timeout-ms", 2000);

        if (endpoint.isBlank() || apiKey.isBlank() || serverId.isBlank()) {
            log(() -> LogUtil.warn("AnalysisA cloud is enabled but endpoint, api-key or server-id is missing"));
            return null;
        }

        final HttpCloudTransport transport =
                new HttpCloudTransport(endpoint, Duration.ofMillis(Math.max(1, timeoutMs)));
        return attempt(transport, new CloudSettings(apiKey, serverId)).orElse(null);
    }

    private static synchronized void scheduleRecovery() {
        if (recoveryScheduled) {
            return;
        }
        recoveryScheduled = true;
        RECOVERY.scheduleWithFixedDelay(
                CloudGateway::recoverIfNeeded,
                RECOVERY_INTERVAL_SECONDS,
                RECOVERY_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    public ModelMetadata metadata() {
        return metadata;
    }

    /** Sends a batch off the packet thread. */
    public CompletableFuture<InferenceResult> inferAsync(final List<FeatureSample> samples) {
        return CompletableFuture.supplyAsync(() -> client.infer(samples), EXECUTOR);
    }

    /** Uploads one recording session's labelled batch off the calling thread. */
    public CompletableFuture<SampleUploadResult> uploadAsync(
            final String sessionId, final Label label, final List<FeatureSample> samples) {
        return CompletableFuture.supplyAsync(
                () -> client.uploadSamples(sessionId, label, samples), EXECUTOR);
    }

    /**
     * Runs a logging call, tolerating a logger that is unavailable.
     *
     * <p>The plugin's logger resolves through the running plugin instance, which does not exist in a
     * headless context and is not yet ready during the earliest startup — and in that state it fails with
     * an {@link Error}, not an {@link Exception}. Letting that propagate would make logging the thing that
     * breaks detection, which is exactly backwards.
     */
    private static void log(final Runnable action) {
        try {
            action.run();
        } catch (Throwable ignored) {
            // see the method comment: a missing logger is not a detection failure
        }
    }

    private static ThreadFactory daemonThreads(final String prefix) {
        return runnable -> {
            final Thread thread = new Thread(runnable, prefix);
            thread.setDaemon(true);
            return thread;
        };
    }
}
