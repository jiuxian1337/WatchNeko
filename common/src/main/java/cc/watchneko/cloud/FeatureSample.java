package cc.watchneko.cloud;

import java.util.Objects;

/**
 * One sample on the wire: a feature vector plus the identifiers needed to route it and match it back.
 *
 * <p>This is the only form in which data leaves the server. Raw angles are not part of the contract, but
 * the player name is: it is sent as-is so the cloud can attribute samples to a person and an operator can
 * find and undo an upload by name. Nothing here is hashed.
 *
 * @param sampleId   plugin-generated id, so a verdict can be matched back to the sample that caused it
 * @param playerName the player's name, in plain text
 * @param features   normalised feature vector, exactly {@link CloudContract#FEATURE_DIMENSIONS} long
 */
public record FeatureSample(String sampleId, String playerName, double[] features) {

    public FeatureSample {
        Objects.requireNonNull(sampleId, "sampleId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(features, "features");
        if (features.length != CloudContract.FEATURE_DIMENSIONS) {
            throw new IllegalArgumentException("a feature vector is " + CloudContract.FEATURE_DIMENSIONS
                    + " long, got " + features.length);
        }
    }
}
