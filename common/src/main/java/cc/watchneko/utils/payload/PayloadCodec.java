package cc.watchneko.utils.payload;

import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;
import java.util.function.Function;

import static com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper.buffer;
import static com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper.copiedBuffer;
import static com.github.retrooper.packetevents.wrapper.PacketWrapper.createUniversalPacketWrapper;

public final class PayloadCodec<P> {
    private final Function<PacketWrapper<?>, P> reader;
    private final BiConsumer<PacketWrapper<?>, P> writer;

    @Contract(pure = true)
    public PayloadCodec(Function<PacketWrapper<?>, P> reader, BiConsumer<PacketWrapper<?>, P> writer) {
        this.reader = reader;
        this.writer = writer;
    }

    public P read(byte[] data) {
        Object buffer = copiedBuffer(data);
        try {
            return reader.apply(createUniversalPacketWrapper(buffer));
        } finally {
            ByteBufHelper.release(buffer);
        }
    }

    public byte @NotNull [] write(P payload) {
        Object buffer = buffer();
        writer.accept(createUniversalPacketWrapper(buffer), payload);
        byte[] bytes = ByteBufHelper.array(buffer);
        ByteBufHelper.release(buffer);
        return bytes;
    }
}
