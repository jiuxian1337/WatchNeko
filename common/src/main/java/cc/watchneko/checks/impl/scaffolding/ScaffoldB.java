package cc.watchneko.checks.impl.scaffolding;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.WatchNekoAPI;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.ScaffoldCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.BlockPlace;
import cc.watchneko.utils.math.GrimMath;
import cc.watchneko.utils.math.Vector3dm;
import cc.watchneko.utils.nmsutil.ReachUtils;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.protocol.world.BlockFace;

import java.util.ArrayDeque;
import java.util.LinkedList;
import java.util.List;

@CheckData(
        name = "ScaffoldB",
        configName = "ScaffoldB",
        decay = 0.0,
        description = ""
)
public final class ScaffoldB extends ScaffoldCheck {
    private static final double MAX_ANGLE = Math.toRadians(90.0D);

    private final List<String> tags = new LinkedList<>();
    private final List<Integer> placeTick = new LinkedList<>();
    private final ArrayDeque<PendingPlacement> pendingPlacements = new ArrayDeque<>();

    private boolean scaffoldAngle;
    private boolean scaffoldTime;
    private int scaffoldTimeAvg;
    private boolean scaffoldSprint;
    private boolean scaffoldRotate;
    private int scaffoldRotateDiff;
    private boolean scaffoldToolSwitch;

    private int sneakTime;
    private int sprintTime;
    private int currentTick;
    private int jumpPhase;
    private boolean cancelNextPlace;
    private int cancelNextPlaceSourceTick = -1;

    public ScaffoldB(PlayerData player) {
        super(player);
    }

    private static boolean isHorizontal(BlockFace face) {
        return face == BlockFace.NORTH || face == BlockFace.SOUTH || face == BlockFace.WEST || face == BlockFace.EAST;
    }

    private static double getAngle(Vector3dm a, Vector3dm b) {
        final double denominator = a.length() * b.length();
        if (denominator <= 0.0D) {
            return 0.0D;
        }

        final double cosine = GrimMath.clamp(a.dot(b) / denominator, -1.0D, 1.0D);
        return Math.acos(cosine);
    }

    @Override
    public void onBlockPlace(final BlockPlace place) {
        if (!place.isBlock) {
            return;
        }

        final int serverTick = WatchNekoAPI.INSTANCE.getTickManager().currentTick;
        processPendingPlacements(serverTick);

        if (player.isSneaking) {
            sneakTime = currentTick;
        }
        currentTick = serverTick;

        final BlockFace placedFace = getPlacedFace(place);
        boolean cancel = false;

        if (isScaffoldPlacement(place, placedFace)) {
            if (cancelNextPlace && Math.abs(cancelNextPlaceSourceTick - currentTick) < 10) {
                cancel = true;
            } else {
                cancel = check(place, placedFace);
            }

            if (!cancel) {
                violations *= 0.98D;
            }
        }

        if (cancel && shouldModifyPackets()) {
            cancel();
        }

        cancelNextPlace = false;
        cancelNextPlaceSourceTick = -1;
        tags.clear();
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        final int serverTick = WatchNekoAPI.INSTANCE.getTickManager().currentTick;
        processPendingPlacements(serverTick);
        currentTick = serverTick;

        if (!isTickPacketIncludingNonMovement(event.getPacketType())) {
            return;
        }

        if (player.isSprinting) {
            sprintTime = currentTick;
        } else if (player.isSneaking) {
            sneakTime = currentTick;
        }

        if (player.packetStateData.packetPlayerOnGround) {
            jumpPhase = 0;
        } else {
            jumpPhase = Math.min(jumpPhase + 1, 20);
        }
    }

    @Override
    public void onReload(ConfigManager config) {
        super.onReload(config);
        scaffoldAngle = config.getBooleanElse("ScaffoldB.angle", true);
        scaffoldTime = config.getBooleanElse("ScaffoldB.time.active", true);
        scaffoldTimeAvg = config.getIntElse("ScaffoldB.time.average", 2);
        scaffoldSprint = config.getBooleanElse("ScaffoldB.sprint", true);
        scaffoldRotate = config.getBooleanElse("ScaffoldB.rotate.active", true);
        scaffoldRotateDiff = config.getIntElse("ScaffoldB.rotate.difference", 90);
        scaffoldToolSwitch = config.getBooleanElse("ScaffoldB.tool-switch", true);
    }

    private boolean check(BlockPlace place, BlockFace placedFace) {
        boolean cancel = false;

        if (scaffoldAngle) {
            final Vector3dm placedVector = new Vector3dm(placedFace.getModX(), placedFace.getModY(), placedFace.getModZ());
            final Vector3dm look = ReachUtils.getLook(player, player.xRot, player.yRot);
            final double placedAngle = getAngle(look, placedVector);

            if (placedAngle > MAX_ANGLE) {
                tags.add("Angle");
                if (flagAndAlert("tags=" + String.join("+", tags)) && shouldCancel()) {
                    cancel();
                    cancel = true;
                    player.mitigateDamage();
                }
            }
        }

        if (scaffoldTime
                && !place.isCancelled()
                && Math.abs(player.yRot) > 70.0F
                && (currentTick - sneakTime) > 3
                && player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.SPEED).isEmpty()) {
            placeTick.add(currentTick);
            if (placeTick.size() > 2) {
                long sum = 0L;
                int lastTick = 0;

                for (int tick : placeTick) {
                    if (lastTick != 0) {
                        sum += tick - lastTick;
                    }
                    lastTick = tick;
                }

                final long avg = sum / placeTick.size();
                if (avg < scaffoldTimeAvg) {
                    tags.add("Time");
                    if (flagAndAlert("tags=" + String.join("+", tags)) && shouldCancel()) {
                        cancel();
                        cancel = true;
                        player.mitigateDamage();
                    }
                    if (placeTick.size() > 20) {
                        placeTick.clear();
                    }
                } else {
                    placeTick.clear();
                }
            }
        }

        final long sprintDiff = currentTick - sprintTime;
        final double yDistance = player.y - player.lastY;
        if (scaffoldSprint
                && Math.abs(player.yRot) > 70.0F
                && sprintDiff < 8
                && yDistance < 0.1D
                && jumpPhase < 4) {
            tags.add("Sprint");
            if (flagAndAlert("tags=" + String.join("+", tags))) {
                cancel();
                cancel = true;
                player.mitigateDamage();
            }
        }

        if (scaffoldRotate || scaffoldToolSwitch) {
            pendingPlacements.addLast(new PendingPlacement(
                    currentTick,
                    player.xRot,
                    player.packetStateData.lastSlotSelected,
                    scaffoldRotate,
                    scaffoldToolSwitch
            ));
        }

        return cancel;
    }

    private void processPendingPlacements(int serverTick) {
        if (serverTick == currentTick) {
            return;
        }

        while (!pendingPlacements.isEmpty() && pendingPlacements.peekFirst().tick != serverTick) {
            final PendingPlacement pending = pendingPlacements.pollFirst();

            if (pending.checkRotate) {
                final float diff = Math.abs(pending.yaw - player.xRot);
                if (diff > scaffoldRotateDiff) {
                    tags.add("Rotate");
                    if (flagAndAlert("tags=" + String.join("+", tags)) && shouldCancel()) {
                        cancel();
                        cancelNextPlace = true;
                        player.mitigateDamage();
                    }
                    cancelNextPlaceSourceTick = pending.tick;
                }
                tags.clear();
            }

            if (pending.checkToolSwitch) {
                if (pending.slot != player.packetStateData.lastSlotSelected) {
                    tags.add("ToolSwitch");
                    if (flagAndAlert("tags=" + String.join("+", tags)) && shouldCancel()) {
                        cancel();
                        cancelNextPlace = true;
                        player.mitigateDamage();
                    }
                    cancelNextPlaceSourceTick = pending.tick;
                }
                tags.clear();
            }
        }
    }

    boolean isBridgePlacement(BlockPlace place) {
        return place.isBlock && isScaffoldPlacement(place, getPlacedFace(place));
    }

    private boolean isScaffoldPlacement(BlockPlace place, BlockFace placedFace) {
        if (placedFace == null) {
            return false;
        }

        final double yDelta = player.y - place.getPlacedBlockPos().getY();
        return isHorizontal(placedFace)
                && yDelta < 2.0D
                && yDelta >= 1.0D
                && place.material.isSolid()
                && new Vector3dm(player.x, player.y, player.z).distance(new Vector3dm(
                place.getPlacedBlockPos().getX(),
                place.getPlacedBlockPos().getY(),
                place.getPlacedBlockPos().getZ()
        )) < 2.0D;
    }

    private BlockFace getPlacedFace(BlockPlace place) {
        if (place.replaceClicked || place.getFace() == BlockFace.OTHER) {
            return null;
        }
        return place.getFace().getOppositeFace();
    }

    private record PendingPlacement(int tick, float yaw, int slot, boolean checkRotate,
                                    boolean checkToolSwitch) {
    }
}
