package cc.watchneko.utils.latency;

import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import io.netty.channel.Channel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Outbound movement state is confirmed by a transaction after its packet or completed bundle. */
public final class MovementReceipts {
    private final IntSupplier lastSent;
    private final BiConsumer<Integer, Runnable> onReceipt;
    private final Runnable sendReceipt;
    private final Consumer<Runnable> defer;
    private List<Update> bundle;
    private long world;
    private int requiredReceipt;
    private boolean scheduled;

    public MovementReceipts(PlayerData player) {
        this(player.lastTransactionSent::get, player.latencyUtils::addRealTimeTask, () -> {
            Channel channel = (Channel) player.user.getChannel();
            if (!channel.isOpen()) return;
            player.sendTransaction();
            channel.flush();
        }, task -> ((Channel) player.user.getChannel()).eventLoop().execute(task));
    }

    MovementReceipts(IntSupplier lastSent, BiConsumer<Integer, Runnable> onReceipt, Runnable sendReceipt,
                     Consumer<Runnable> defer) {
        this.lastSent = lastSent;
        this.onReceipt = onReceipt;
        this.sendReceipt = sendReceipt;
        this.defer = defer;
    }

    public void bundleDelimiter(PacketSendEvent event) {
        event.getTasksAfterSend().add(() -> {
            if (!event.isCancelled()) bundleWritten();
        });
    }

    void bundleWritten() {
        if (bundle == null) {
            bundle = new ArrayList<>();
        } else {
            List<Update> completed = bundle;
            bundle = null;
            int receipt = lastSent.getAsInt() + 1;
            for (Update update : completed) queue(update, receipt);
            scheduleReceipt();
        }
    }

    public void after(PacketSendEvent event, Runnable written, IntConsumer received) {
        event.getTasksAfterSend().add(() -> {
            if (!event.isCancelled()) packetWritten(written, received);
        });
    }

    public void packetWritten(Runnable written, IntConsumer received) {
        Update update = new Update(world, written, received);
        if (bundle == null) {
            queue(update, lastSent.getAsInt() + 1);
            scheduleReceipt();
        } else {
            bundle.add(update);
        }
    }

    public void beginWorld() {
        world++;
        if (bundle != null) bundle.clear();
        requiredReceipt = lastSent.getAsInt();
    }

    private void queue(Update update, int receipt) {
        update.written.run();
        onReceipt.accept(receipt, () -> {
            if (update.world == world) update.received.accept(receipt);
        });
        requiredReceipt = receipt;
    }

    private void scheduleReceipt() {
        if (scheduled || lastSent.getAsInt() >= requiredReceipt) return;
        scheduled = true;
        defer.accept(() -> {
            scheduled = false;
            if (bundle == null && lastSent.getAsInt() < requiredReceipt) sendReceipt.run();
        });
    }

    private record Update(long world, Runnable written, IntConsumer received) {}
}
