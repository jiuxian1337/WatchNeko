package cc.watchneko.utils.latency;

import cc.watchneko.WatchNekoAPI;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.LogUtil;
import cc.watchneko.utils.anticheat.MessageUtil;
import cc.watchneko.utils.common.GrimArguments;

public class LatencyUtils {
    private final LatencyTaskQueue transactionMap = new LatencyTaskQueue();
    private final PlayerData player;

    public LatencyUtils(PlayerData player) {
        this.player = player;
    }

    public void addRealTimeTask(int transaction, Runnable runnable) {
        addRealTimeTask(transaction, false, runnable);
    }

    public void addRealTimeTaskAsync(int transaction, Runnable runnable) {
        addRealTimeTask(transaction, true, runnable);
    }

    public void addRealTimeTask(int transaction, boolean async, Runnable runnable) {
        synchronized (transactionMap) {
            if (player.lastTransactionReceived.get() < transaction) {
                transactionMap.add(transaction, runnable);
                return;
            }
        }
        if (async) {
            player.runSafely(runnable);
        } else {
            runnable.run();
        }
    }

    public void addRealTimeTaskWithNextTransaction(int transaction, Runnable first, Runnable next) {
        synchronized (transactionMap) {
            if (player.lastTransactionReceived.get() < transaction) {
                transactionMap.addWithNextTransaction(transaction, first, next);
                return;
            }
        }
        first.run();
        addRealTimeTask(transaction + 1, next);
    }

    public void handleNettySyncTransaction(int transaction) {
        LatencyTaskQueue.TaskNode ready = transactionMap.drainReady(transaction);
        LatencyTaskQueue.TaskNode released = null;
        while (ready != null) {
            LatencyTaskQueue.TaskNode following = ready.next();
            Runnable runnable = ready.runnable();
            released = ready.clearAndLink(released);
            ready = following;
            try {
                runnable.run();
            } catch (Exception e) {
                LogUtil.error("An error has occurred when running transactions for player: " + player.user.getName(), e);
                if (GrimArguments.TRANSACTION_KICKS) {
                    player.disconnect(MessageUtil.miniMessage(MessageUtil.replacePlaceholders(player, WatchNekoAPI.INSTANCE.getConfigManager().getDisconnectPacketError())));
                }
            }
        }
        transactionMap.recycle(released);
    }
}
