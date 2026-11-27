package cc.watchneko.utils.latency;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MovementReceiptsTest {
    private final LatencyTaskQueue queue = new LatencyTaskQueue();
    private final ArrayDeque<Runnable> eventLoop = new ArrayDeque<>();
    private final List<String> changes = new ArrayList<>();
    private int sent;
    private final MovementReceipts receipts = new MovementReceipts(() -> sent, queue::add, () -> sent++, eventLoop::add);

    @Test
    void standalonePacketsShareAReceiptAndKeepTheirWriteOrder() {
        packet("chunk");
        packet("block");
        assertEquals(List.of("write chunk", "write block"), changes);
        assertEquals(1, eventLoop.size());
        flush();
        assertEquals(1, sent);
        acknowledge(0);
        assertEquals(2, changes.size());
        acknowledge(1);
        assertEquals(List.of("write chunk", "write block", "read chunk 1", "read block 1"), changes);
    }

    @Test
    void aBundleCanOnlyBeProvenAfterItsClosingDelimiter() {
        packet("before");
        receipts.bundleWritten();
        packet("spawn");
        packet("move");
        flush();
        assertEquals(List.of("write before"), changes);
        assertEquals(0, sent);

        receipts.bundleWritten();
        flush();
        acknowledge(1);
        assertEquals(List.of("write before", "write spawn", "write move", "read before 1", "read spawn 1", "read move 1"), changes);
    }

    @Test
    void replacementDropsOldPendingUpdatesAndOrdersTheNewWorld() {
        packet("old chunk");
        flush();
        receipts.bundleWritten();
        packet("old entity");
        receipts.beginWorld();
        packet("respawn");
        packet("new chunk");
        receipts.bundleWritten();
        acknowledge(1);
        assertEquals(List.of("write old chunk", "write respawn", "write new chunk"), changes);

        flush();
        acknowledge(2);
        assertEquals(List.of("write old chunk", "write respawn", "write new chunk", "read respawn 2", "read new chunk 2"), changes);
    }

    @Test
    void aScheduledTransactionCanProveWrittenPackets() {
        packet("chunk");
        sent++;
        flush();
        assertEquals(1, sent);
        acknowledge(sent);
        assertEquals(List.of("write chunk", "read chunk 1"), changes);
    }

    @Test
    void lateTasksAndContinuationsKeepReceiptOrderWhenAnAnswerIsSkipped() {
        queue.add(9, () -> changes.add("future"));
        queue.addWithNextTransaction(5, () -> changes.add("first"), () -> changes.add("continuation"));
        queue.add(6, () -> changes.add("next"));
        acknowledge(4);
        assertEquals(List.of(), changes);
        acknowledge(6);
        assertEquals(List.of("first", "continuation", "next"), changes);
        acknowledge(9);
        assertEquals(List.of("first", "continuation", "next", "future"), changes);
    }

    private void packet(String name) {
        receipts.packetWritten(() -> changes.add("write " + name), receipt -> changes.add("read " + name + " " + receipt));
    }

    private void flush() {
        while (!eventLoop.isEmpty()) eventLoop.remove().run();
    }

    private void acknowledge(int receipt) {
        for (var node = queue.drainReady(receipt); node != null; node = node.next()) {
            node.runnable().run();
        }
    }
}
