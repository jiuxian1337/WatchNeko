package cc.watchneko.checks.impl.xray;

import com.github.retrooper.packetevents.util.Vector3i;
import org.jetbrains.annotations.Nullable;

/** A confirmed world change, with evidence captured before the block was removed. */
public record MiningSample(String material, @Nullable Ore ore, @Nullable AirSample nearbyAir) {
    public enum Environment {
        ENCLOSED, OPEN, UNKNOWN
    }

    public enum Discovery {
        FIRST, PREVIOUS, UNKNOWN
    }

    public record Ore(Discovery discovery, int size, int faceConnectedSize,
                      Environment blastEnvironment, Environment pathEnvironment, Environment smallVeinEnvironment,
                      boolean blastKnown, long blastId, boolean exactBlastMatch, @Nullable Reveal reveal) {}

    public record Reveal(Vector3i openedAt, long nanos) {}

    public record AirSample(int artificial, int total) {}
}
