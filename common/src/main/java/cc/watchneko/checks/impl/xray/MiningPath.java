package cc.watchneko.checks.impl.xray;

import com.github.retrooper.packetevents.util.Vector3i;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MiningPath {
    private static final Comparator<Column> COLUMN_ORDER = Comparator.comparingInt((Column column) -> column.x)
            .thenComparingInt(column -> column.z).thenComparingInt(column -> column.floor);

    record Metrics(int blocks, int turns, int branches, int yShifts, boolean straight, Set<Vector3i> connected) {}

    @Nullable
    static Metrics measure(Vector3i start, Vector3i ore, Vector3i opener, Set<Vector3i> excavated, int yShiftMin) {
        if (MiningGeometry.distanceSquared(ore, opener) != 1 || !excavated.contains(opener)) return null;
        Set<Vector3i> cells = new HashSet<>(excavated);
        cells.add(start);
        // Collapse vertically contiguous cells before measuring bends and branches.
        Map<Vector3i, Column> columns = columns(cells);
        Column startColumn = columns.get(start);
        Column endColumn = columns.get(opener);
        Map<Column, Column> parents = new HashMap<>();
        ArrayDeque<Column> queue = new ArrayDeque<>();
        parents.put(startColumn, null);
        queue.add(startColumn);
        while (!queue.isEmpty()) {
            Column column = queue.remove();
            for (Column neighbor : column.neighbors.stream().sorted(COLUMN_ORDER).toList()) {
                if (parents.containsKey(neighbor)) continue;
                parents.put(neighbor, column);
                queue.add(neighbor);
            }
        }
        if (!parents.containsKey(endColumn)) return null;
        List<Column> route = new ArrayList<>();
        for (Column column = endColumn; column != null; column = parents.get(column)) route.add(column);
        Collections.reverse(route);

        Set<Vector3i> connected = new HashSet<>();
        for (Vector3i cell : excavated) {
            if (!cell.equals(start) && parents.containsKey(columns.get(cell))) connected.add(cell);
        }
        int previousY = start.y;
        int minY = previousY;
        int maxY = minY;
        int turns = 0;
        int previousX = 0;
        int previousZ = 0;
        int yShifts = 0;
        int verticalDirection = 0;
        int verticalTravel = 0;
        for (int index = 1; index < route.size(); index++) {
            Column previous = route.get(index - 1);
            Column current = route.get(index);
            int dx = current.x - previous.x;
            int dz = current.z - previous.z;
            if (index > 1 && (dx != previousX || dz != previousZ)) turns++;
            previousX = dx;
            previousZ = dz;

            int y = index == route.size() - 1 ? opener.y : current.floor;
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            int dy = y - previousY;
            previousY = y;
            if (dy == 0) continue;
            int direction = Integer.signum(dy);
            if (direction != verticalDirection) {
                if (verticalTravel >= yShiftMin) yShifts++;
                verticalDirection = direction;
                verticalTravel = 0;
            }
            verticalTravel += Math.abs(dy);
        }
        if (verticalTravel >= yShiftMin) yShifts++;
        if (route.size() == 1 && Math.abs(opener.y - start.y) >= yShiftMin) yShifts = 1;
        double displacement = Math.hypot(endColumn.x - startColumn.x, endColumn.z - startColumn.z);
        boolean straight = displacement >= 10 && maxY - minY <= 2 && displacement >= 0.66 * (route.size() - 1);
        return new Metrics(connected.size(), turns, branches(route), yShifts, straight, Set.copyOf(connected));
    }

    private static Map<Vector3i, Column> columns(Set<Vector3i> cells) {
        Map<Vector3i, Column> columns = new HashMap<>();
        for (Vector3i cell : cells) {
            if (cells.contains(MiningGeometry.offset(cell, 0, -1, 0))) continue;
            Column column = new Column(cell.x, cell.y, cell.z);
            Vector3i position = cell;
            do {
                columns.put(position, column);
                position = MiningGeometry.offset(position, 0, 1, 0);
            } while (cells.contains(position));
        }
        for (Map.Entry<Vector3i, Column> entry : columns.entrySet()) {
            Vector3i position = entry.getKey();
            Column column = entry.getValue();
            connect(column, columns.get(MiningGeometry.offset(position, 1, 0, 0)));
            connect(column, columns.get(MiningGeometry.offset(position, 0, 0, 1)));
        }
        return columns;
    }

    private static void connect(Column a, @Nullable Column b) {
        if (b == null) return;
        a.neighbors.add(b);
        b.neighbors.add(a);
    }

    private static int branches(List<Column> route) {
        Set<Column> mainRoute = new HashSet<>(route);
        Set<Column> visited = new HashSet<>();
        ArrayDeque<Column> queue = new ArrayDeque<>();
        int branches = 0;
        for (Column attachment : route) {
            for (Column neighbor : attachment.neighbors) {
                if (mainRoute.contains(neighbor) || !visited.add(neighbor)) continue;
                boolean rejoins = false;
                queue.add(neighbor);
                while (!queue.isEmpty()) {
                    for (Column next : queue.remove().neighbors) {
                        if (mainRoute.contains(next)) {
                            rejoins |= next != attachment;
                        } else if (visited.add(next)) {
                            queue.add(next);
                        }
                    }
                }
                if (!rejoins) branches++;
            }
        }
        return branches;
    }

    private static final class Column {
        private final int x;
        private final int floor;
        private final int z;
        private final Set<Column> neighbors = new HashSet<>(4);

        Column(int x, int floor, int z) {
            this.x = x;
            this.floor = floor;
            this.z = z;
        }
    }
}
