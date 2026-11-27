package cc.watchneko.checks.impl.crash;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.payload.PayloadBookEdit;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.FireworkExplosion;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemAdventurePredicate;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTByteArray;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTIntArray;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTLongArray;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientCustomClickAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCustomClickAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEditBook;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPickItemFromBlock;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPickItemFromEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;

import java.util.List;

@CheckData(name = "CrashM", description = "Item metadata and nested content limits", experimental = true, setback = -1)
public final class CrashM extends ExperimentalCrashCheck {
    private int maxDialogBytes;
    private Limits limits;

    public CrashM(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        limits = new Limits(setting(config, "max-items-per-packet", 512, 8192),
                setting(config, "max-item-depth", 6, 24),
                setting(config, "max-nbt-elements", 8192, 100000),
                setting(config, "max-nbt-depth", 48, 96),
                setting(config, "max-book-page-bytes", 4096, 65536));
        maxDialogBytes = setting(config, "max-dialog-bytes", 32768, 262144);
    }

    @Override
    void checkPacket(PacketReceiveEvent event) {
        var type = event.getPacketType();
        if (type == PacketType.Play.Client.CUSTOM_CLICK_ACTION || type == PacketType.Configuration.Client.CUSTOM_CLICK_ACTION) {
            checkDialog(event, type == PacketType.Play.Client.CUSTOM_CLICK_ACTION);
            return;
        }
        if (type == PacketType.Play.Client.PICK_ITEM_FROM_BLOCK || type == PacketType.Play.Client.PICK_ITEM_FROM_ENTITY) {
            checkPickItem(event, type == PacketType.Play.Client.PICK_ITEM_FROM_BLOCK);
            return;
        }
        var version = event.getServerVersion();
        boolean components = version.isNewerThanOrEquals(ServerVersion.V_1_20_5);
        if (type == PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
            if (player.gamemode != GameMode.CREATIVE) return;
            checkStack(event, new WrapperPlayClientCreativeInventoryAction(event).getItemStack(), components);
        } else if (type == PacketType.Play.Client.CLICK_WINDOW) {
            var click = new WrapperPlayClientClickWindow(event);
            if (click.getHashedSlots() != null) return;
            ItemBudget budget = new ItemBudget(limits);
            for (var stack : click.getSlots().orElseGet(java.util.Collections::emptyMap).values()) {
                if (!budget.accepts(stack, components, 0)) {
                    reject(event, "inventory-item-budget");
                    return;
                }
            }
            if (!budget.accepts(click.getCarriedItemStack(), components, 0)) reject(event, "cursor-item-budget");
        } else if (type == PacketType.Play.Client.EDIT_BOOK && version.isOlderThan(ServerVersion.V_1_17)) {
            checkStack(event, new WrapperPlayClientEditBook(event).getItemStack(), false);
        } else if (type == PacketType.Play.Client.PLUGIN_MESSAGE && version.isOlderThan(ServerVersion.V_1_13)) {
            var message = new WrapperPlayClientPluginMessage(event);
            if (!CrashJ.isLegacyBookChannel(message.getChannelName())) return;
            checkStack(event, PayloadBookEdit.CODEC.read(message.getData()).itemStack(), false);
        }
    }

    private void checkDialog(PacketReceiveEvent event, boolean play) {
        if (ByteBufHelper.readableBytes(event.getByteBuf()) > maxDialogBytes) {
            reject(event, "dialog-payload-size");
            return;
        }
        NBT payload = play ? new WrapperPlayClientCustomClickAction(event).getPayload()
                : new WrapperConfigClientCustomClickAction(event).getPayload();
        if (!new ItemBudget(limits).acceptsNbt(payload, 0)) reject(event, "dialog-nbt-budget");
    }

    private void checkPickItem(PacketReceiveEvent event, boolean block) {
        if (player.gamemode == null || player.gamemode == GameMode.CREATIVE) return;
        boolean requestsMetadata = block ? new WrapperPlayClientPickItemFromBlock(event).isIncludeData()
                : new WrapperPlayClientPickItemFromEntity(event).isIncludeData();
        if (requestsMetadata) reject(event, "pick-item-metadata-without-creative");
    }

    private void checkStack(PacketReceiveEvent event, ItemStack item, boolean components) {
        if (!new ItemBudget(limits).accepts(item, components, 0)) reject(event, "item-metadata-budget");
    }

    record Limits(int items, int itemDepth, int nbtElements, int nbtDepth, int bookPageBytes) {}

    static final class ItemBudget {
        private static final int MAX_COMPONENT_PATCHES = 256;
        private static final int MAX_BOOK_PAGES = 100;
        private static final int MAX_NESTED_SLOTS = 96;
        private static final int MAX_FIREWORK_PARTS = 32;
        private static final int MAX_ARRAY = 65536;
        private final Limits limits;
        private int items;
        private int nbtElements;

        ItemBudget(Limits limits) {
            this.limits = limits;
        }

        boolean accepts(ItemStack item, boolean components, int depth) {
            if (item == null || item.isEmpty()) return true;
            if (++items > limits.items || depth > limits.itemDepth) return false;
            NBTCompound nbt = item.getNBT();
            if (!acceptsNbt(nbt, 0) || !acceptsLegacyBook(item, nbt) || !acceptsLegacyFireworks(nbt)) return false;
            if (!components) return true;
            if (!item.hasComponentPatches()) return true;
            if (item.getComponents().getPatches().size() > MAX_COMPONENT_PATCHES) return false;
            return acceptsBookComponents(item) && acceptsTextComponents(item) && acceptsFireworks(item)
                    && acceptsOtherComponents(item) && acceptsNestedItems(item, depth);
        }

        boolean acceptsNbt(NBT nbt, int depth) {
            if (nbt == null) return true;
            if (++nbtElements > limits.nbtElements || depth > limits.nbtDepth) return false;
            if (nbt instanceof NBTCompound compound) {
                var tags = compound.getTags();
                if (tags.size() > limits.nbtElements - nbtElements) return false;
                for (NBT child : tags.values()) {
                    if (!acceptsNbt(child, depth + 1)) return false;
                }
            } else if (nbt instanceof NBTList<?> list) {
                if (list.size() > limits.nbtElements - nbtElements) return false;
                for (NBT child : list.getTags()) {
                    if (!acceptsNbt(child, depth + 1)) return false;
                }
            } else if (nbt instanceof NBTByteArray bytes) {
                return bytes.getValue().length <= MAX_ARRAY;
            } else if (nbt instanceof NBTIntArray ints) {
                return ints.getValue().length <= MAX_ARRAY / Integer.BYTES;
            } else if (nbt instanceof NBTLongArray longs) {
                return longs.getValue().length <= MAX_ARRAY / Long.BYTES;
            }
            return true;
        }

        private boolean acceptsLegacyBook(ItemStack item, NBTCompound nbt) {
            if (nbt == null) return true;
            var resolved = nbt.getNumberTagOrNull("resolved");
            if (resolved != null && resolved.getAsInt() != 0 && !item.is(ItemTypes.WRITTEN_BOOK)) return false;
            if (!item.is(ItemTypes.WRITABLE_BOOK) && !item.is(ItemTypes.WRITTEN_BOOK)) return true;
            var pages = nbt.getStringListTagOrNull("pages");
            if (pages == null) return true;
            if (pages.size() > MAX_BOOK_PAGES) return false;
            for (NBTString page : pages.getTags()) {
                if (utf8Bytes(page.getValue()) > limits.bookPageBytes) return false;
            }
            return true;
        }

        private boolean acceptsBookComponents(ItemStack item) {
            var writable = item.getComponentOr(ComponentTypes.WRITABLE_BOOK_CONTENT, null);
            if (writable != null) {
                if (writable.getPages().size() > MAX_BOOK_PAGES) return false;
                for (var page : writable.getPages()) {
                    if (utf8Bytes(page.getRaw()) > limits.bookPageBytes || utf8Bytes(page.getFiltered()) > limits.bookPageBytes) {
                        return false;
                    }
                }
            }
            var written = item.getComponentOr(ComponentTypes.WRITTEN_BOOK_CONTENT, null);
            if (written == null) return true;
            if (written.getPages().size() > MAX_BOOK_PAGES || written.isResolved() && !item.is(ItemTypes.WRITTEN_BOOK)) return false;
            for (var page : written.getPages()) {
                if (CrashItemText.size(page.getRaw(), limits.bookPageBytes) > limits.bookPageBytes
                        || CrashItemText.size(page.getFiltered(), limits.bookPageBytes) > limits.bookPageBytes) return false;
            }
            return true;
        }

        private boolean acceptsTextComponents(ItemStack item) {
            final int limit = 32768;
            if (CrashItemText.size(item.getComponentOr(ComponentTypes.CUSTOM_NAME, null), limit) > limit
                    || CrashItemText.size(item.getComponentOr(ComponentTypes.ITEM_NAME, null), limit) > limit) return false;
            var lore = item.getComponentOr(ComponentTypes.LORE, null);
            if (lore == null) return true;
            if (lore.getLines().size() > 256) return false;
            int remaining = limit;
            for (var line : lore.getLines()) {
                remaining -= CrashItemText.size(line, remaining);
                if (remaining < 0) return false;
            }
            return true;
        }

        private static boolean acceptsLegacyFireworks(NBTCompound nbt) {
            if (nbt == null) return true;
            var explosion = nbt.getCompoundTagOrNull("Explosion");
            if (explosion != null && !acceptsColors(explosion)) return false;
            var fireworks = nbt.getCompoundTagOrNull("Fireworks");
            if (fireworks == null) return true;
            var explosions = fireworks.getCompoundListTagOrNull("Explosions");
            return explosions == null || explosions.size() <= MAX_FIREWORK_PARTS
                    && explosions.getTags().stream().allMatch(ItemBudget::acceptsColors);
        }

        private static boolean acceptsColors(NBTCompound explosion) {
            return acceptsColors(explosion.getTagOrNull("Colors")) && acceptsColors(explosion.getTagOrNull("FadeColors"));
        }

        private static boolean acceptsColors(NBT tag) {
            return !(tag instanceof NBTIntArray colors) || colors.getValue().length <= MAX_FIREWORK_PARTS;
        }

        private static boolean acceptsFireworks(ItemStack item) {
            var explosion = item.getComponentOr(ComponentTypes.FIREWORK_EXPLOSION, null);
            if (explosion != null && !acceptsColors(explosion)) return false;
            var fireworks = item.getComponentOr(ComponentTypes.FIREWORKS, null);
            return fireworks == null || fireworks.getExplosions().size() <= MAX_FIREWORK_PARTS
                    && fireworks.getExplosions().stream().allMatch(ItemBudget::acceptsColors);
        }

        private static boolean acceptsColors(FireworkExplosion explosion) {
            return explosion.getColors().size() <= MAX_FIREWORK_PARTS && explosion.getFadeColors().size() <= MAX_FIREWORK_PARTS;
        }

        private boolean acceptsOtherComponents(ItemStack item) {
            if (!acceptsNbt(item.getComponentOr(ComponentTypes.CUSTOM_DATA, null), 0)) return false;
            var tool = item.getComponentOr(ComponentTypes.TOOL, null);
            if (tool != null && tool.getRules().size() > 128) return false;
            var profile = item.getComponentOr(ComponentTypes.PROFILE, null);
            if (profile != null) {
                if (profile.getProperties().size() > 32) return false;
                int bytes = 0;
                for (var property : profile.getProperties()) {
                    bytes += utf8Bytes(property.getName()) + utf8Bytes(property.getValue()) + utf8Bytes(property.getSignature());
                    if (bytes > 16384) return false;
                }
            }
            return acceptsPredicates(item.getComponentOr(ComponentTypes.CAN_BREAK, null))
                    && acceptsPredicates(item.getComponentOr(ComponentTypes.CAN_PLACE_ON, null));
        }

        private boolean acceptsPredicates(ItemAdventurePredicate predicates) {
            if (predicates == null) return true;
            if (predicates.getPredicates().size() > 192) return false;
            int properties = 0;
            for (var predicate : predicates.getPredicates()) {
                if (predicate.getProperties() != null) properties += predicate.getProperties().size();
                if (properties > 256 || !acceptsNbt(predicate.getNbt(), 0)) return false;
            }
            return true;
        }

        private boolean acceptsNestedItems(ItemStack item, int depth) {
            var container = item.getComponentOr(ComponentTypes.CONTAINER, null);
            if (container != null && !acceptsNested(container.getItems(), depth, MAX_NESTED_SLOTS)) return false;
            var bundle = item.getComponentOr(ComponentTypes.BUNDLE_CONTENTS, null);
            if (bundle != null && !acceptsNested(bundle.getItems(), depth, MAX_NESTED_SLOTS)) return false;
            var projectiles = item.getComponentOr(ComponentTypes.CHARGED_PROJECTILES, null);
            return projectiles == null || acceptsNested(projectiles.getItems(), depth, 16);
        }

        private boolean acceptsNested(List<ItemStack> stacks, int depth, int maximum) {
            if (stacks.size() > maximum) return false;
            for (ItemStack stack : stacks) {
                if (!accepts(stack, true, depth + 1)) return false;
            }
            return true;
        }
    }
}
