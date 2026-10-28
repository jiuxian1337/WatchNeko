package cc.watchneko.cloud;

/**
 * How a sample is identified on the wire.
 *
 * <p>The id carries the player's name, so a sample can be traced back to the person who produced it
 * without a lookup table. Names are not unique across a tenant's history (a player can rename, and an
 * offline-mode server can have two players with the same name), so the id also carries a session id and a
 * counter: the cloud stores samples by id and rejects duplicates.
 */
public final class SampleIdentity {

    private SampleIdentity() {
    }

    /**
     * The wire id for the {@code counter}-th sample of a player.
     *
     * <p>Use this for samples sent to inference. A verdict is consumed immediately and never stored under
     * this id, so collisions across sessions do not matter there.
     *
     * @param playerName the player's name
     * @param counter    a per-player monotonic counter, so verdicts can be matched back to samples
     */
    public static String of(final String playerName, final long counter) {
        return name(playerName) + "-" + counter;
    }

    /**
     * The wire id for the {@code counter}-th sample of one recording session.
     *
     * <p>Recording samples are stored under their id, and the cloud rejects an id it already has — so this
     * id has to be unique across every session the tenant ever uploads, not merely within one player's
     * connection. A plain per-player counter is not enough: it lives on the player's check instance, which
     * is rebuilt on every login, so a player who reconnects restarts at 1 and re-sends ids that are already
     * stored. The whole second recording is then rejected as duplicates.
     *
     * <p>Scoping to the session id fixes that, because the session id is a random UUID minted by
     * {@code record start} and is therefore unique across reconnects, restarts and servers.
     *
     * @param playerName the player's name
     * @param sessionId  the recording session the sample belongs to
     * @param counter    a monotonic counter within that session
     */
    public static String forRecording(final String playerName, final String sessionId, final long counter) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("a recording sample needs its session id");
        }
        return name(playerName) + "-" + sessionId + "-" + counter;
    }

    /** Names may contain spaces or characters that would make an id hard to read, so normalise them. */
    private static String name(final String playerName) {
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("a sample needs its player name");
        }
        return playerName.trim().replace(' ', '_');
    }
}
