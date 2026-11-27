package cc.watchneko.events.packets;

import cc.watchneko.checks.impl.crash.CrashN;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.exception.PacketProcessException;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.PacketSide;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.viaversion.viaversion.api.Via;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

/** Retains client movement input that a ViaVersion translation may consume or replace. */
public final class ViaMovementInput extends ChannelInboundHandlerAdapter {
    private static final String NAME = "watchneko-via-movement-input";
    private static final PacketEntityAction ACTION = new PacketEntityAction();
    private static final PacketPlayerSteer STEER = new PacketPlayerSteer();

    private final PlayerData player;

    private ViaMovementInput(PlayerData player) {
        this.player = player;
    }

    public static void install(PlayerData player) {
        player.runSafely(() -> {
            if (!(player.user.getChannel() instanceof Channel channel) || !channel.isOpen()) return;
            var pipeline = channel.pipeline();
            String viaDecoder = Via.getManager().getInjector().getDecoderName();
            if (pipeline.get(viaDecoder) != null && pipeline.get(NAME) == null) {
                pipeline.addBefore(viaDecoder, NAME, new ViaMovementInput(player));
                player.movementSimulation.setReadsTranslatedTickEnd(player.movementSimulation.isUnified()
                        && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                        && PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_21_2));
            }
        });
    }

    public static void remove(PlayerData player) {
        if (!(player.user.getChannel() instanceof Channel channel) || !channel.isOpen()) return;
        player.runSafely(() -> {
            if (channel.pipeline().get(NAME) != null) channel.pipeline().remove(NAME);
            player.movementSimulation.setReadsTranslatedTickEnd(false);
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        var state = player.user.getDecoderState();
        if (msg instanceof ByteBuf frame && (state == ConnectionState.PLAY || state == ConnectionState.CONFIGURATION)
                && player.checkManager.getCheck(CrashN.class).discardBeforeTranslation(frame.readableBytes())) {
            ReferenceCountUtil.release(msg);
            return;
        }
        ClientVersion client = player.user.getClientVersion();
        if (!(msg instanceof ByteBuf buffer) || client == null || state != ConnectionState.PLAY
                || client == PacketEvents.getAPI().getServerManager().getVersion().toClientVersion()) {
            ctx.fireChannelRead(msg);
            return;
        }

        boolean previous = player.packetStateData.preViaMovementInput;
        try {
            MovementInputEvent input = decode(ctx, buffer, client);
            if (input != null) {
                if (input.getPacketType() == PacketType.Play.Client.CLIENT_TICK_END) {
                    // ViaVersion consumes this packet on servers whose protocol has no tick-end packet.
                    // Keep the frame valid for checks after any translated packets have been handled.
                    buffer.retain();
                    try {
                        ctx.fireChannelRead(msg);
                        PacketPlayerDigging.updateItemUseOnTick(player);
                        CheckManagerListener.handlePlayPacket(player, input);
                        if (input.hasPostTasks()) input.getPostTasks().forEach(Runnable::run);
                    } finally {
                        buffer.release();
                    }
                    return;
                }
                player.packetStateData.preViaMovementInput = false;
                if (input.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) ACTION.onPacketReceive(input);
                else STEER.onPacketReceive(input);
                if (input.isCancelled()) {
                    ReferenceCountUtil.release(msg);
                    return;
                }
                player.packetStateData.preViaMovementInput = true;
            }
            ctx.fireChannelRead(msg);
        } finally {
            player.packetStateData.preViaMovementInput = previous;
        }
    }

    private MovementInputEvent decode(ChannelHandlerContext ctx, ByteBuf buffer, ClientVersion client) {
        ByteBuf duplicate = buffer.duplicate();
        try {
            int id = PacketWrapper.createUniversalPacketWrapper(duplicate, client.toServerVersion()).readVarInt();
            PacketTypeCommon type = PacketType.getById(PacketSide.CLIENT, ConnectionState.PLAY, client, id);
            if (type == PacketType.Play.Client.ENTITY_ACTION || type == PacketType.Play.Client.PLAYER_INPUT
                    || type == PacketType.Play.Client.STEER_VEHICLE
                    || (type == PacketType.Play.Client.CLIENT_TICK_END && player.movementSimulation.readsTranslatedTickEnd())
                    || (type == PacketType.Play.Client.PLAYER_ROTATION && player.inVehicle())) {
                return new MovementInputEvent(id, type, client, ctx, player, duplicate);
            }
        } catch (IndexOutOfBoundsException | PacketProcessException ignored) {
            // The normal inbound decoder owns malformed packet handling.
        }
        return null;
    }

    private static final class MovementInputEvent extends PacketReceiveEvent {
        MovementInputEvent(int id, PacketTypeCommon type, ClientVersion version, ChannelHandlerContext ctx,
                           PlayerData player, ByteBuf payload) throws PacketProcessException {
            super(id, type, version.toServerVersion(), ctx.channel(), player.user,
                    player.platformPlayer == null ? null : player.platformPlayer.getNative(), payload);
        }
    }
}
