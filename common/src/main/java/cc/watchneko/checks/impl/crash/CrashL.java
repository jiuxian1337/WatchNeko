package cc.watchneko.checks.impl.crash;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@CheckData(name = "CrashL", description = "Packet flood and inventory processing rate", experimental = true, setback = -1)
public final class CrashL extends ExperimentalCrashCheck {
    private static final Set<PacketTypeCommon> LIMITED = Set.of(PacketType.Play.Client.CLICK_WINDOW,
            PacketType.Play.Client.CREATIVE_INVENTORY_ACTION, PacketType.Play.Client.TAB_COMPLETE,
            PacketType.Play.Client.CRAFT_RECIPE_REQUEST, PacketType.Play.Client.SET_DISPLAYED_RECIPE,
            PacketType.Play.Client.UPDATE_SIGN, PacketType.Play.Client.PLUGIN_MESSAGE,
            PacketType.Configuration.Client.PLUGIN_MESSAGE);
    private RateWindow total;
    private RateWindow commands;
    private RateWindow inventoryBytes;
    private RateWindow changedSlots;
    private RateWindow dialogBytes;
    private Map<PacketTypeCommon, RateWindow> byType;
    private int maxPackets;
    private int maxType;
    private int maxCommands;
    private int maxInventoryBytes;
    private int maxChangedSlots;
    private int maxDialogBytes;

    public CrashL(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        maxPackets = setting(config, "max-packets-per-second", 750, 20000);
        maxType = setting(config, "max-packets-per-type-per-second", 120, 20000);
        maxCommands = setting(config, "max-commands-per-second", 30, 20000);
        maxInventoryBytes = setting(config, "max-inventory-bytes-per-second", 512 * 1024, 32 * 1024 * 1024);
        maxChangedSlots = setting(config, "max-changed-slots-per-second", 1024, 100000);
        maxDialogBytes = setting(config, "max-dialog-bytes-per-second", 256 * 1024, 8 * 1024 * 1024);
        total = new RateWindow();
        commands = new RateWindow();
        inventoryBytes = new RateWindow();
        changedSlots = new RateWindow();
        dialogBytes = new RateWindow();
        byType = new HashMap<>();
    }

    @Override
    void checkPacket(PacketReceiveEvent event) {
        long now = System.nanoTime();
        var type = event.getPacketType();
        if (limit(event, total, now, 1, maxPackets, "packet-rate")) return;
        if (LIMITED.contains(type) && limit(event, byType.computeIfAbsent(type, ignored -> new RateWindow()), now,
                1, maxType, "packet-type-rate type=" + type)) return;
        if (isCommand(event) && limit(event, commands, now, 1, maxCommands, "command-request-rate")) return;
        if (type == PacketType.Play.Client.CUSTOM_CLICK_ACTION || type == PacketType.Configuration.Client.CUSTOM_CLICK_ACTION) {
            limit(event, dialogBytes, now, ByteBufHelper.readableBytes(event.getByteBuf()), maxDialogBytes, "dialog-byte-rate");
            return;
        }
        boolean click = type == PacketType.Play.Client.CLICK_WINDOW;
        if (!click && type != PacketType.Play.Client.CREATIVE_INVENTORY_ACTION && type != PacketType.Play.Client.EDIT_BOOK) return;
        if (limit(event, inventoryBytes, now, ByteBufHelper.readableBytes(event.getByteBuf()), maxInventoryBytes,
                "inventory-byte-rate") || !click) return;

        var packet = new WrapperPlayClientClickWindow(event);
        var hashed = packet.getHashedSlots();
        int count = hashed == null ? packet.getSlots().map(Map::size).orElse(0) : hashed.size();
        limit(event, changedSlots, now, count, maxChangedSlots, "inventory-slot-rate");
    }

    private static boolean isCommand(PacketReceiveEvent event) {
        var type = event.getPacketType();
        return type == PacketType.Play.Client.CHAT_COMMAND || type == PacketType.Play.Client.CHAT_COMMAND_UNSIGNED
                || type == PacketType.Play.Client.CHAT_MESSAGE && event.getServerVersion().isOlderThan(ServerVersion.V_1_19)
                && new WrapperPlayClientChatMessage(event).getMessage().startsWith("/");
    }
}
