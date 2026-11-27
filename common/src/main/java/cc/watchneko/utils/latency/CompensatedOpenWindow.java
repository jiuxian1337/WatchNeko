package cc.watchneko.utils.latency;

import java.util.HashSet;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Tracks the screens a Java client could be showing on its connection's packet thread.
 * Based on the split-state window tracking in CultAC, originally from Grim PR #2848 by ManInMyVan.
 */
public final class CompensatedOpenWindow {
    public static final int PLAYER_INVENTORY = 0;
    private static final int CLOSED = -1;

    // Each entry represents a possible last server window change and the screens the client could have after it.
    private final NavigableMap<Long, Set<Integer>> possibleWindows = new TreeMap<>();
    private long nextChange;
    private long confirmedServerClose;

    public CompensatedOpenWindow() {
        possibleWindows.put(0L, new HashSet<>(Set.of(CLOSED)));
    }

    public ServerChange serverOpen(int windowId, boolean mayStayClosed) {
        return new ServerChange(++nextChange, windowId, mayStayClosed);
    }

    public ServerChange serverClose() {
        return new ServerChange(++nextChange, CLOSED, false);
    }

    public void mayHaveApplied(ServerChange change) {
        if (change.sequence() <= possibleWindows.firstKey() || possibleWindows.containsKey(change.sequence())) {
            return;
        }

        Set<Integer> screens = new HashSet<>(Set.of(change.windowId()));
        if (change.mayStayClosed()) {
            screens.add(CLOSED);
        }
        possibleWindows.put(change.sequence(), screens);
    }

    public void confirm(ServerChange change) {
        mayHaveApplied(change);
        possibleWindows.headMap(change.sequence(), false).clear();
    }

    public void confirmServerClose(ServerChange change) {
        if (change.windowId() == CLOSED && change.sequence() >= possibleWindows.firstKey()) {
            confirmedServerClose = Math.max(confirmedServerClose, change.sequence());
        }
        confirm(change);
    }

    public long getConfirmedServerClose() {
        return confirmedServerClose;
    }

    public void onPlayerInventoryOpened() {
        observe(PLAYER_INVENTORY, PLAYER_INVENTORY, true);
    }

    public void onContainerClick(int windowId) {
        if (windowId < 0) return;
        observe(windowId, windowId, false);
    }

    /** Returns the server open sequence if the client packet identifies a single container instance. */
    public long getContainerSequence(int windowId) {
        if (windowId <= PLAYER_INVENTORY) return -1;
        long sequence = -1;
        for (var entry : possibleWindows.entrySet()) {
            if (!entry.getValue().contains(windowId)) continue;
            if (sequence != -1) return -1;
            sequence = entry.getKey();
        }
        return sequence;
    }

    public void onClientClose(int windowId) {
        if (windowId < 0) return;
        observe(windowId, CLOSED, false);
    }

    public void mayHaveClosed() {
        possibleWindows.lastEntry().getValue().add(CLOSED);
    }

    private void observe(int windowId, int resultingWindow, boolean mayOpenPlayerInventory) {
        Long firstMatch = null;
        for (var entry : possibleWindows.entrySet()) {
            if (matches(entry.getValue(), windowId, mayOpenPlayerInventory)) {
                firstMatch = entry.getKey();
                break;
            }
        }
        if (firstMatch == null) return;

        // It took at least this many server changes to produce the observed client packet.
        possibleWindows.headMap(firstMatch, false).clear();
        Set<Integer> screens = possibleWindows.get(firstMatch);
        screens.clear();
        screens.add(resultingWindow);

        // A later server change may apply before or after this client packet was sent.
        for (Set<Integer> laterScreens : possibleWindows.tailMap(firstMatch, false).values()) {
            if (matches(laterScreens, windowId, mayOpenPlayerInventory)) {
                laterScreens.add(resultingWindow);
            }
        }
    }

    private static boolean matches(Set<Integer> screens, int windowId, boolean mayOpenPlayerInventory) {
        return screens.contains(windowId) || mayOpenPlayerInventory && screens.contains(CLOSED);
    }

    public boolean mustBeOpen() {
        for (Set<Integer> screens : possibleWindows.values()) {
            if (screens.contains(CLOSED)) return false;
        }
        return true;
    }

    public boolean mustBeClosed() {
        for (Set<Integer> screens : possibleWindows.values()) {
            if (screens.size() != 1 || !screens.contains(CLOSED)) return false;
        }
        return true;
    }

    public record ServerChange(long sequence, int windowId, boolean mayStayClosed) {}
}
