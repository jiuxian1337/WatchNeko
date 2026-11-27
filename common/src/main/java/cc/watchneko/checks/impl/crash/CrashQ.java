package cc.watchneko.checks.impl.crash;

import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.recipe.RecipeDisplayId;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCraftRecipeRequest;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSetDisplayedRecipe;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRecipeBookAdd;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

@CheckData(name = "CrashQ", description = "Invalid numeric recipe requests", experimental = true, setback = -1)
public final class CrashQ extends ExperimentalCrashCheck {
    private static final int MAX_TRACKED_DISPLAYS = 16384;
    private final IntOpenHashSet advertised = new IntOpenHashSet();
    private boolean receivedBook;
    private boolean exceededCapacity;

    public CrashQ(PlayerData player) {
        super(player);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled() || event.getPacketType() != PacketType.Play.Server.RECIPE_BOOK_ADD
                || !checksAdvertisement(event.getServerVersion())
                || player.getClientVersion() != event.getServerVersion().toClientVersion()) return;
        if (!active()) {
            advertised.clear();
            receivedBook = false;
            exceededCapacity = false;
            return;
        }
        var book = new WrapperPlayServerRecipeBookAdd(event);
        event.getTasksAfterSend().add(() -> {
            if (event.isCancelled() || exceededCapacity) return;
            // Keep sent IDs across removals so an in-flight request using the previous book remains valid.
            for (var entry : book.getEntries()) {
                advertised.add(entry.getContents().getId().getId());
                if (advertised.size() > MAX_TRACKED_DISPLAYS) {
                    advertised.clear();
                    exceededCapacity = true;
                    return;
                }
            }
            receivedBook |= book.isReplace();
        });
    }

    @Override
    void checkPacket(PacketReceiveEvent event) {
        if (event.getServerVersion().isOlderThan(ServerVersion.V_1_21_2)) return;
        RecipeDisplayId recipe;
        if (event.getPacketType() == PacketType.Play.Client.CRAFT_RECIPE_REQUEST) {
            recipe = new WrapperPlayClientCraftRecipeRequest(event).getRecipeId();
        } else if (event.getPacketType() == PacketType.Play.Client.SET_DISPLAYED_RECIPE) {
            recipe = new WrapperPlayClientSetDisplayedRecipe(event).getRecipeId();
        } else {
            return;
        }
        if (recipe == null) return;
        int id = recipe.getId();
        if (id < 0) {
            reject(event, "negative-recipe-id");
        } else if (receivedBook && !exceededCapacity && !advertised.contains(id)
                && player.getClientVersion() == event.getServerVersion().toClientVersion()) {
            reject(event, "unadvertised-recipe-id");
        }
    }

    private static boolean checksAdvertisement(ServerVersion version) {
        return version == ServerVersion.V_1_21_2 || version == ServerVersion.V_1_21_3;
    }
}
