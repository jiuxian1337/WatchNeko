package cc.watchneko.command.commands;

import cc.watchneko.WatchNekoAPI;
import cc.watchneko.cloud.CloudGateway;
import cc.watchneko.cloud.Label;
import cc.watchneko.cloud.RecordingSession;
import cc.watchneko.cloud.RecordingSessions;
import cc.watchneko.cloud.SampleUploadResult;
import cc.watchneko.command.BuildableCommand;
import cc.watchneko.command.CommandUtils;
import cc.watchneko.platform.api.command.PlayerSelector;
import cc.watchneko.platform.api.player.PlatformPlayer;
import cc.watchneko.platform.api.sender.Sender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Records a player's samples as training data, labelled at the start and resolved at the end.
 *
 * <pre>
 *   /watchneko record start &lt;player&gt; &lt;cheat|legit&gt;
 *   /watchneko record stop  &lt;player&gt; &lt;upload|discard&gt;
 * </pre>
 *
 * <p>The label is chosen before recording rather than after, because the admin knows what they are about to
 * record. The session is then either uploaded as one labelled batch or thrown away, so a bad recording
 * costs nothing and never reaches the dataset.
 *
 * <p>Recording needs an authenticated, reachable cloud — somewhere to upload the samples to. It does
 * <em>not</em> need a published model: the first model is trained from
 * exactly the samples a model-less server records. Requiring one here would make the first model
 * impossible to produce.
 */
public class GrimRecord implements BuildableCommand {

    private static final String PERMISSION = "watchneko.record";

    /** The only two labels a session can carry. */
    private static final SuggestionProvider<Sender> LABEL_SUGGESTIONS =
            CommandUtils.fromStrings("cheat", "legit");

    /** The only two ways to resolve a stopped session. */
    private static final SuggestionProvider<Sender> ACTION_SUGGESTIONS =
            CommandUtils.fromStrings("upload", "discard");

    /**
     * The names of players who are currently being recorded.
     *
     * <p>Read at completion time rather than captured once: sessions start and stop while the server runs,
     * and a fixed list would offer names that are no longer recorded.
     */
    private static final SuggestionProvider<Sender> RECORDED_PLAYER_SUGGESTIONS =
            SuggestionProvider.blocking((context, input) -> RecordingSessions.all().stream()
                    .map(RecordingSession::playerName)
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .map(Suggestion::suggestion)
                    .toList());

    @Override
    public void register(final CommandManager<Sender> commandManager) {
        commandManager.command(
                commandManager.commandBuilder("watchneko")
                        .literal("record")
                        .literal("start")
                        .permission(PERMISSION)
                        .required("target", WatchNekoAPI.INSTANCE.getCommandAdapter().singlePlayerSelectorParser(),
                                WatchNekoAPI.INSTANCE.getCommandAdapter().onlinePlayerSuggestions())
                        .required("label", StringParser.stringParser(), LABEL_SUGGESTIONS)
                        .handler(this::handleStart));

        commandManager.command(
                commandManager.commandBuilder("watchneko")
                        .literal("record")
                        .literal("stop")
                        .permission(PERMISSION)
                        // A name, not a player selector: the player may well have disconnected, and the
                        // whole point of stop is that an interrupted recording can still be resolved.
                        .required("target", StringParser.stringParser(), RECORDED_PLAYER_SUGGESTIONS)
                        .required("action", StringParser.stringParser(), ACTION_SUGGESTIONS)
                        .handler(this::handleStop));
    }

    private void handleStart(final @NonNull CommandContext<Sender> context) {
        final Sender sender = context.sender();
        final PlatformPlayer target = resolveTarget(context, sender);
        if (target == null) {
            return;
        }

        final Optional<Label> label = parseLabel(context.<String>get("label"));
        if (label.isEmpty()) {
            sender.sendMessage(Component.text("Label must be 'cheat' or 'legit'.", NamedTextColor.RED));
            return;
        }

        if (CloudGateway.current() == null) {
            sender.sendMessage(Component.text(
                    "Cloud is not available, so nothing could be recorded or uploaded.",
                    NamedTextColor.RED));
            return;
        }

        final UUID uuid = target.getUniqueId();
        final String sessionId = "rs-" + UUID.randomUUID();

        final Optional<RecordingSession> started =
                RecordingSessions.start(uuid, target.getName(), label.get(), sessionId);

        if (started.isEmpty()) {
            final String state = RecordingSessions.active(uuid)
                    .filter(RecordingSession::isClosed)
                    .map(session -> "has a stopped recording of " + session.size()
                            + " samples that has not been uploaded or discarded yet; resolve it first")
                    .orElse("is already being recorded; stop that session first");
            sender.sendMessage(Component.text(target.getName() + " " + state + ".", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text()
                .append(Component.text("Recording ", NamedTextColor.GRAY))
                .append(Component.text(target.getName(), NamedTextColor.AQUA))
                .append(Component.text(" as ", NamedTextColor.GRAY))
                .append(Component.text(label.get().name().toLowerCase(Locale.ROOT), NamedTextColor.YELLOW))
                .append(Component.text(". Session " + sessionId, NamedTextColor.GRAY))
                .append(cloudHasNoModel()
                        ? Component.text(
                                " (no model published yet; samples are being collected as training data)",
                                NamedTextColor.GRAY)
                        : Component.empty())
                .build());
    }

    /** Whether the gateway in effect is the cold start: reachable, but nothing published to classify with. */
    private static boolean cloudHasNoModel() {
        final CloudGateway gateway = CloudGateway.current();
        return gateway != null && !gateway.canInfer();
    }

    private void handleStop(final @NonNull CommandContext<Sender> context) {
        final Sender sender = context.sender();
        final String action = String.valueOf(context.<String>get("action")).toLowerCase(Locale.ROOT);

        // Validate before touching the session. A mistyped action must not cost the admin their recording.
        if (!action.equals("upload") && !action.equals("discard")) {
            sender.sendMessage(Component.text(
                    "Action must be 'upload' or 'discard'; nothing was changed.", NamedTextColor.RED));
            return;
        }

        // Keyed by the player being recorded, not by whoever ran the command: an admin recording someone
        // else must still be able to end that session. Matched by name rather than by selector so this works
        // after the player has disconnected — otherwise a recording whose player never returns could never
        // be ended, and its samples would sit in memory until the plugin restarts.
        final String name = String.valueOf(context.<String>get("target")).trim();

        final Optional<RecordingSession> found = RecordingSessions.findByName(name);
        if (found.isEmpty()) {
            sender.sendMessage(Component.text(
                    name + " is not being recorded.", NamedTextColor.RED));
            return;
        }

        final RecordingSession session = found.get();
        // Closing freezes the sample set. It is idempotent, so running stop again after a failed upload
        // closes an already-closed session and retries the very same samples.
        session.close();

        if (action.equals("discard")) {
            RecordingSessions.stop(session.player());
            sender.sendMessage(Component.text(
                    "Discarded " + session.size() + " samples from " + session.playerName()
                            + describeInterruptions(session) + ".",
                    NamedTextColor.GRAY));
            return;
        }

        upload(sender, session);
    }

    /**
     * Uploads a closed session, keeping it in the registry unless the upload succeeds.
     *
     * <p>The samples are the expensive part — they cannot be re-recorded, because the moment has passed.
     * A failed upload therefore changes nothing: the admin fixes whatever was wrong and runs the same
     * command again.
     */
    private void upload(final @NonNull Sender sender, final RecordingSession session) {
        final CloudGateway gateway = CloudGateway.current();
        if (gateway == null) {
            sender.sendMessage(Component.text(
                    "Cloud is not available; " + session.size() + " samples from " + session.playerName()
                            + " are kept. Fix the cloud settings, then run this command again to retry.",
                    NamedTextColor.RED));
            return;
        }

        if (!session.beginUpload()) {
            sender.sendMessage(Component.text(
                    "An upload of " + session.playerName() + "'s samples is already in flight.",
                    NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text(
                "Uploading " + session.size() + " samples from " + session.playerName()
                        + describeInterruptions(session) + "...",
                NamedTextColor.GRAY));

        gateway.uploadAsync(session.sessionId(), session.label(), session.samples())
                .whenComplete((result, error) -> {
                    session.endUpload();
                    if (error != null) {
                        // Kept, not dropped: the admin can retry the same batch with the same command.
                        sender.sendMessage(Component.text(
                                "Upload failed; " + session.size() + " samples from "
                                        + session.playerName() + " are kept. Run "
                                        + "'/watchneko record stop " + session.playerName() + " upload'"
                                        + " again to retry. Cause: " + describeError(error),
                                NamedTextColor.RED));
                        return;
                    }
                    // Only now is the recording resolved and safe to forget. Conditional on identity: an
                    // admin who discarded this session and started a new recording for the same player
                    // must not lose that new recording to this late callback.
                    RecordingSessions.stopIfCurrent(session.player(), session);
                    sender.sendMessage(describe(result, session));
                });
    }

    /** The most useful part of a failed upload's cause, without the wrapper noise. */
    private static String describeError(final Throwable error) {
        final Throwable cause = error.getCause() != null ? error.getCause() : error;
        return cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    /**
     * Says whether the recording was interrupted, so an admin can decide to discard it before uploading.
     *
     * <p>A recording that spans a disconnect is still valid training data — each sample stands on its own —
     * but it is not the continuous run the admin thought they were making, and they should know.
     */
    private static String describeInterruptions(final RecordingSession session) {
        if (session.interruptions() == 0) {
            return "";
        }
        return " (interrupted " + session.interruptions() + " time"
                + (session.interruptions() == 1 ? "" : "s") + " by disconnects)";
    }

    private static Component describe(final SampleUploadResult result, final RecordingSession session) {
        return Component.text()
                .append(Component.text("Uploaded ", NamedTextColor.GRAY))
                .append(Component.text(String.valueOf(result.accepted()), NamedTextColor.GREEN))
                .append(Component.text(" samples for ", NamedTextColor.GRAY))
                .append(Component.text(session.playerName(), NamedTextColor.AQUA))
                .append(Component.text(" as ", NamedTextColor.GRAY))
                .append(Component.text(session.label().name().toLowerCase(Locale.ROOT), NamedTextColor.YELLOW))
                .append(Component.text(
                        result.rejected() > 0 ? " (" + result.rejected() + " rejected)" : "",
                        NamedTextColor.RED))
                .build();
    }

    private static PlatformPlayer resolveTarget(
            final @NonNull CommandContext<Sender> context, final Sender sender) {
        final PlayerSelector selector = context.getOrDefault("target", null);
        if (selector == null) {
            return null;
        }
        final PlatformPlayer target = selector.getSinglePlayer().getPlatformPlayer();
        if (target == null) {
            sender.sendMessage(Component.text("That player is not on this server.", NamedTextColor.RED));
            return null;
        }
        return target;
    }

    private static Optional<Label> parseLabel(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "cheat" -> Optional.of(Label.CHEAT);
            case "legit" -> Optional.of(Label.LEGIT);
            default -> Optional.empty();
        };
    }
}
