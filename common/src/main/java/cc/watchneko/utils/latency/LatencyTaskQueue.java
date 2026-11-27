package cc.watchneko.utils.latency;

/**
 * Adapted from CultAC's transaction queue at 8327a703 (GPL-3.0).
 * Tasks waiting for a transaction, in one list in insertion order. A task waits for the current transaction or the
 * next one (an update outside a bundle is proven by the next ping), so no task is more than one below a task before
 * it, and a drain stops at the first task two past the answered transaction. A task's continuation takes its place at
 * the next transaction once it runs. A task added further below, such as a Bedrock receipt callback for an earlier
 * Java proof, goes before the first task two or more above it, which keeps that true.
 */
final class LatencyTaskQueue {
    private static final int MAX_RECYCLED_TASKS = 256;
    private TaskNode head;
    private TaskNode tail;
    /** The highest transaction added since the list was last empty. */
    private int highest = Integer.MIN_VALUE;
    private TaskNode recycledTasks;
    private int recycledTaskCount;

    synchronized void add(int transaction, Runnable runnable) {
        insert(acquire(transaction, runnable, null));
    }

    /**
     * Runs {@code runnable} at {@code transaction}, then {@code next} at the one after in {@code runnable}'s place:
     * right after it when both are ready, otherwise before the tasks added after it.
     */
    synchronized void addWithNextTransaction(int transaction, Runnable runnable, Runnable next) {
        insert(acquire(transaction, runnable, next));
    }

    private void insert(TaskNode task) {
        int transaction = task.transaction;
        if (head == null || transaction >= (long) highest - 1) {
            if (tail == null) head = task;
            else tail.next = task;
            tail = task;
            if (transaction > highest) highest = transaction;
            return;
        }
        TaskNode previous = null;
        TaskNode current = head;
        while (current != null && current.transaction < (long) transaction + 2) {
            previous = current;
            current = current.next;
        }
        task.next = current;
        if (previous == null) head = task;
        else previous.next = task;
        if (current == null) tail = task;
    }

    synchronized TaskNode drainReady(int transaction) {
        TaskNode readyHead = null;
        TaskNode readyTail = null;
        TaskNode previous = null;
        TaskNode current = head;
        // No task behind one two past the answered transaction can be ready.
        while (current != null && current.transaction <= (long) transaction + 1) {
            TaskNode next = current.next;
            if (current.transaction > transaction) {
                previous = current;
                current = next;
                continue;
            }
            TaskNode continuation = current.continuation == null
                    ? null
                    : acquire(current.transaction + 1, current.continuation, null);
            current.continuation = null;
            if (continuation != null && continuation.transaction > transaction) {
                // Not ready yet: the continuation takes its task's place. Every task left before it is at its
                // transaction, so the list stays in order.
                continuation.next = next;
                if (previous == null) head = continuation;
                else previous.next = continuation;
                if (tail == current) tail = continuation;
                if (continuation.transaction > highest) highest = continuation.transaction;
                previous = continuation;
            } else {
                if (previous == null) head = next;
                else previous.next = next;
                if (tail == current) tail = previous;
            }
            current.next = null;
            if (readyHead == null) readyHead = current;
            else readyTail.next = current;
            readyTail = current;
            if (continuation != null && continuation.transaction <= transaction) {
                readyTail.next = continuation;
                readyTail = continuation;
            }
            current = next;
        }
        if (head == null) highest = Integer.MIN_VALUE;
        return readyHead;
    }

    private TaskNode acquire(int transaction, Runnable runnable, Runnable continuation) {
        TaskNode task = recycledTasks;
        if (task == null) return new TaskNode(transaction, runnable, continuation);
        recycledTasks = task.next;
        recycledTaskCount--;
        task.transaction = transaction;
        task.runnable = runnable;
        task.continuation = continuation;
        task.next = null;
        return task;
    }

    // The caller must finish walking the drained batch before returning any of its nodes.
    synchronized void recycle(TaskNode task) {
        if (task == null || recycledTaskCount == MAX_RECYCLED_TASKS) return;
        TaskNode tail = task;
        int count = 1;
        while (recycledTaskCount + count < MAX_RECYCLED_TASKS && tail.next != null) {
            tail = tail.next;
            count++;
        }
        tail.next = recycledTasks;
        recycledTasks = task;
        recycledTaskCount += count;
    }

    static final class TaskNode {
        private int transaction;
        private Runnable runnable;
        /** Runs at the next transaction, in this task's place; null when there is none. */
        private Runnable continuation;
        private TaskNode next;

        private TaskNode(int transaction, Runnable runnable, Runnable continuation) {
            this.transaction = transaction;
            this.runnable = runnable;
            this.continuation = continuation;
        }

        Runnable runnable() {
            return runnable;
        }

        TaskNode next() {
            return next;
        }

        void clear() {
            runnable = null;
            continuation = null;
            next = null;
        }

        TaskNode clearAndLink(TaskNode released) {
            clear();
            next = released;
            return this;
        }
    }
}
