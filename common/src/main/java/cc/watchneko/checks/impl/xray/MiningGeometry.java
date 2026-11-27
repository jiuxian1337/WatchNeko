package cc.watchneko.checks.impl.xray;

import com.github.retrooper.packetevents.util.Vector3i;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

public final class MiningGeometry {
    private MiningGeometry() {}

    public enum OreCell {
        ORE, OTHER, UNKNOWN
    }

    public record Vein(Set<Vector3i> members, boolean complete) {
        public Vein {
            members = Set.copyOf(members);
        }

        public int faceConnectedSize(Vector3i start) {
            Set<Vector3i> visited = new HashSet<>();
            ArrayDeque<Vector3i> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);
            while (!queue.isEmpty()) {
                for (Vector3i neighbor : faces(queue.remove())) {
                    if (members.contains(neighbor) && visited.add(neighbor)) queue.add(neighbor);
                }
            }
            return visited.size();
        }
    }

    public static Vein scanVein(Vector3i start, int radius, int budget, Function<Vector3i, OreCell> reader) {
        Set<Vector3i> members = new HashSet<>();
        Set<Vector3i> visited = new HashSet<>();
        ArrayDeque<Vector3i> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        boolean complete = true;
        int reads = 0;
        while (!queue.isEmpty() && reads < budget) {
            Vector3i position = queue.remove();
            reads++;
            OreCell cell = reader.apply(position);
            if (cell == OreCell.UNKNOWN) complete = false;
            if (cell != OreCell.ORE) continue;
            members.add(position);
            boolean atBoundary = distanceSquared(start, position) > (long) radius * radius;
            if (atBoundary) {
                // An ore immediately outside the radius proves that the in-radius component is incomplete.
                members.remove(position);
                complete = false;
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Vector3i neighbor = offset(position, dx, dy, dz);
                        if (visited.add(neighbor)) queue.add(neighbor);
                    }
                }
            }
        }
        return new Vein(members, complete && queue.isEmpty() && members.contains(start));
    }

    public static Vector3i[] faces(Vector3i position) {
        return new Vector3i[] {offset(position, 1, 0, 0), offset(position, -1, 0, 0),
                offset(position, 0, 1, 0), offset(position, 0, -1, 0),
                offset(position, 0, 0, 1), offset(position, 0, 0, -1)};
    }

    public static Vector3i offset(Vector3i position, int x, int y, int z) {
        return new Vector3i(position.x + x, position.y + y, position.z + z);
    }

    public static long distanceSquared(Vector3i a, Vector3i b) {
        long dx = (long) a.x - b.x;
        long dy = (long) a.y - b.y;
        long dz = (long) a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }

}
