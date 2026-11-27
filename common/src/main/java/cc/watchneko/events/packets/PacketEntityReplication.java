package cc.watchneko.events.packets;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.WatchNekoAPI;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.impl.combat.FairReach;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.LogUtil;
import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import cc.watchneko.utils.data.TrackerData;
import cc.watchneko.utils.data.packetentity.PacketEntity;
import cc.watchneko.utils.data.packetentity.PacketEntityHook;
import cc.watchneko.utils.data.packetentity.PacketEntityTrackXRot;
import cc.watchneko.utils.reflection.ViaVersionUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.potion.PotionType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class PacketEntityReplication extends Check implements PacketCheck {

    private final AtomicBoolean hasSentPreWavePacket = new AtomicBoolean(true);

    // Let's imagine the player is on a boat.
    // The player breaks this boat
    // If we were to despawn the boat without an extra transaction, then the boat would disappear before
    // it disappeared on the client side, creating a ghost boat to flag checks with
    //
    // If we were to despawn the tick after, spawning must occur the transaction before to stop the same exact
    // problem with ghost boats in reverse.
    //
    // Therefore, we despawn the transaction after, and spawn the tick before.
    //
    // If we despawn then spawn an entity in the same transaction, then this solution would despawn the new entity
    // instead of the old entity, so we wouldn't see the boat at all
    //
    // Therefore, if the server sends a despawn and then a spawn in the same transaction for the same entity,
    // We should simply add a transaction (which will clear this list!)
    //
    // Another valid solution is to simply spam more transactions, but let's not waste bandwidth.
    private final List<Integer> despawnedEntitiesThisTransaction = new ArrayList<>();

    // Maximum ping when a firework boost is removed from the player.
    private int maxFireworkBoostPing = 1000;

    public PacketEntityReplication(PlayerData player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (player.movementSimulation.isUnified() || !isTickPacket(event.getPacketType())) return;
        tickInterpolation();
    }

    public void tickInterpolation() {
        boolean unified = player.movementSimulation.isUnified();
        player.compensatedEntities.entitiesRemovedThisTick.clear();
        boolean isTickingReliably = player.isTickingReliablyFor(3);

        PacketEntity playerVehicle = player.compensatedEntities.self.getRiding();
        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            if (unified && player.compensatedEntities.isUntrackedRider(entity)) continue;
            if (unified && entity instanceof cc.watchneko.utils.data.packetentity.PacketEntityShulker shulker) shulker.tickPeek();
            if (entity == playerVehicle && !player.vehicleData.lastDummy) {
                // The player has this as their vehicle, so they aren't interpolating it.
                // And it isn't a dummy position
                entity.setPositionRaw(player, entity.getPossibleLocationBoxes());
            } else {
                entity.onMovement(isTickingReliably);
            }
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled()) return;
        // ensure grim is the one that sent the transaction
        if ((event.getPacketType() == PacketType.Play.Server.PING || event.getPacketType() == PacketType.Play.Server.WINDOW_CONFIRMATION) && player.packetStateData.lastServerTransWasValid) {
            despawnedEntitiesThisTransaction.clear();
        } else if (event.getPacketType() == PacketType.Play.Server.SPAWN_LIVING_ENTITY) {
            WrapperPlayServerSpawnLivingEntity packetOutEntity = new WrapperPlayServerSpawnLivingEntity(event);
            addEntity(event, packetOutEntity.getEntityId(), packetOutEntity.getEntityUUID(), packetOutEntity.getEntityType(), packetOutEntity.getPosition(), packetOutEntity.getYaw(), packetOutEntity.getPitch(), packetOutEntity.getEntityMetadata(), 0);
        } else if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
            WrapperPlayServerSpawnEntity packetOutEntity = new WrapperPlayServerSpawnEntity(event);
            addEntity(event, packetOutEntity.getEntityId(), packetOutEntity.getUUID().orElse(null), packetOutEntity.getEntityType(), packetOutEntity.getPosition(), packetOutEntity.getYaw(), packetOutEntity.getPitch(), null, packetOutEntity.getData());
        } else if (event.getPacketType() == PacketType.Play.Server.SPAWN_PLAYER) {
            WrapperPlayServerSpawnPlayer packetOutEntity = new WrapperPlayServerSpawnPlayer(event);
            addEntity(event, packetOutEntity.getEntityId(), packetOutEntity.getUUID(), EntityTypes.PLAYER, packetOutEntity.getPosition(), packetOutEntity.getYaw(), packetOutEntity.getPitch(), packetOutEntity.getEntityMetadata(), 0);
        } else if (event.getPacketType() == PacketType.Play.Server.SPAWN_PAINTING) {
            WrapperPlayServerSpawnPainting packetOutEntity = new WrapperPlayServerSpawnPainting(event);
            addEntity(event, packetOutEntity.getEntityId(), packetOutEntity.getUUID(), EntityTypes.PAINTING, packetOutEntity.getPosition().toVector3d(), 0, 0f, null, packetOutEntity.getDirection().getHorizontalIndex());
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
            WrapperPlayServerEntityRelativeMove move = new WrapperPlayServerEntityRelativeMove(event);
            handleMoveEntity(event, move.getEntityId(), move.getDeltaX(), move.getDeltaY(), move.getDeltaZ(), null, null, true, true);
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
            WrapperPlayServerEntityRelativeMoveAndRotation move = new WrapperPlayServerEntityRelativeMoveAndRotation(event);
            handleMoveEntity(event, move.getEntityId(), move.getDeltaX(), move.getDeltaY(), move.getDeltaZ(), move.getYaw() * 0.7111111F, move.getPitch() * 0.7111111F, true, true);
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_TELEPORT) {
            WrapperPlayServerEntityTeleport move = new WrapperPlayServerEntityTeleport(event);
            Vector3d pos = move.getPosition();
            if (player.movementSimulation.isUnified()) {
                TrackerData tracked = player.compensatedEntities.getTrackedEntity(move.getEntityId());
                var relative = move.getRelativeFlags();
                if (tracked != null && relative != null) {
                    pos = new Vector3d(pos.x + (relative.has(com.github.retrooper.packetevents.protocol.teleport.RelativeFlag.X) ? tracked.getX() : 0),
                            pos.y + (relative.has(com.github.retrooper.packetevents.protocol.teleport.RelativeFlag.Y) ? tracked.getY() : 0),
                            pos.z + (relative.has(com.github.retrooper.packetevents.protocol.teleport.RelativeFlag.Z) ? tracked.getZ() : 0));
                }
            }
            handleMoveEntity(event, move.getEntityId(), pos.getX(), pos.getY(), pos.getZ(), move.getYaw(), move.getPitch(), false, true);
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
            // ENTITY_TELEPORT but without relative flags
            WrapperPlayServerEntityPositionSync move = new WrapperPlayServerEntityPositionSync(event);
            final EntityPositionData values = move.getValues();
            final Vector3d pos = values.getPosition();
            // TODO this isn't technically correct
            // If the position sync is to a pos > 4096 from the entity pos, client does some special stuff without interpolation
            handleMoveEntity(event, move.getId(), pos.getX(), pos.getY(), pos.getZ(), values.getYaw(), values.getPitch(), false, true);
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_ROTATION) { // Affects interpolation
            WrapperPlayServerEntityRotation move = new WrapperPlayServerEntityRotation(event);
            handleMoveEntity(event, move.getEntityId(), 0, 0, 0, move.getYaw() * 0.7111111F, move.getPitch() * 0.7111111F, true, false);
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
            WrapperPlayServerEntityMetadata entityMetadata = new WrapperPlayServerEntityMetadata(event);
            scheduleEntityUpdate(event, player.lastTransactionSent.get(), () ->
                    player.compensatedEntities.updateEntityMetadata(entityMetadata.getEntityId(), entityMetadata.getEntityMetadata()));
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_EQUIPMENT) {
            WrapperPlayServerEntityEquipment equipment = new WrapperPlayServerEntityEquipment(event);
            scheduleEntityUpdate(event, player.lastTransactionSent.get(), () ->
                    player.compensatedEntities.updateEntityEquipment(equipment.getEntityId(), equipment.getEquipment()));
        }

        // 1.19.3+
        else if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO_UPDATE) {
            WrapperPlayServerPlayerInfoUpdate info = new WrapperPlayServerPlayerInfoUpdate(event);
            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> {
                for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo entry : info.getEntries()) {
                    final UserProfile gameProfile = entry.getGameProfile();
                    final UUID uuid = gameProfile.getUUID();
                    player.compensatedEntities.profiles.put(uuid, gameProfile);
                }
            });
        } else if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO_REMOVE) {
            WrapperPlayServerPlayerInfoRemove remove = new WrapperPlayServerPlayerInfoRemove(event);
            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> remove.getProfileIds().forEach(player.compensatedEntities.profiles::remove));
        } else if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO) {
            WrapperPlayServerPlayerInfo info = new WrapperPlayServerPlayerInfo(event);
            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> {
                if (info.getAction() == WrapperPlayServerPlayerInfo.Action.ADD_PLAYER) {
                    for (WrapperPlayServerPlayerInfo.PlayerData entry : info.getPlayerDataList()) {
                        final UserProfile gameProfile = entry.getUserProfile();
                        final UUID uuid = gameProfile.getUUID();
                        player.compensatedEntities.profiles.put(uuid, gameProfile);
                    }
                } else if (info.getAction() == WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER) {
                    info.getPlayerDataList().forEach(profile -> player.compensatedEntities.profiles.remove(profile.getUserProfile().getUUID()));
                }
            });
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_EFFECT) {
            WrapperPlayServerEntityEffect effect = new WrapperPlayServerEntityEffect(event);

            PotionType type = effect.getPotionType();

            // ViaVersion tries faking levitation effects and fails badly lol, flagging the anticheat
            // Block other effects just in case ViaVersion gets any ideas
            //
            // Set to 24 so ViaVersion blocks it
            // 24 is the levitation effect
            if (player.getClientVersion().isOlderThan(ClientVersion.V_1_9) && ViaVersionUtil.isAvailable && type.getId(player.getClientVersion()) > 23) {
                event.setCancelled(true);
                return;
            }

            // ViaVersion dolphin's grace also messes us up, set it to a potion effect that doesn't exist on 1.12
            // Effect 31 is bad omen
            if (player.getClientVersion().isOlderThan(ClientVersion.V_1_13) && ViaVersionUtil.isAvailable && type.getId(player.getClientVersion()) == 30) {
                event.setCancelled(true);
                return;
            }

            if (!player.movementSimulation.isUnified() && isDirectlyAffectingPlayer(player, effect.getEntityId())) {
                player.sendTransaction();
            }

            scheduleEntityUpdate(event, player.lastTransactionSent.get(), () -> {
                PacketEntity entity = player.compensatedEntities.getEntity(effect.getEntityId());
                if (entity == null) return;

                entity.addPotionEffect(type, effect.getEffectAmplifier());
            });
        } else if (event.getPacketType() == PacketType.Play.Server.REMOVE_ENTITY_EFFECT) {
            WrapperPlayServerRemoveEntityEffect effect = new WrapperPlayServerRemoveEntityEffect(event);

            if (!player.movementSimulation.isUnified() && isDirectlyAffectingPlayer(player, effect.getEntityId())) {
                player.sendTransaction();
            }

            scheduleEntityUpdate(event, player.lastTransactionSent.get(), () -> {
                PacketEntity entity = player.compensatedEntities.getEntity(effect.getEntityId());
                if (entity == null) return;

                entity.removePotionEffect(effect.getPotionType());
            });
        } else if (event.getPacketType() == PacketType.Play.Server.UPDATE_ATTRIBUTES) {
            WrapperPlayServerUpdateAttributes attributes = new WrapperPlayServerUpdateAttributes(event);

            int entityID = attributes.getEntityId();

            // The attributes for this entity is active, currently
            if (!player.movementSimulation.isUnified() && isDirectlyAffectingPlayer(player, entityID)) {
                player.sendTransaction();
            }

            scheduleEntityUpdate(event, player.lastTransactionSent.get(),
                    () -> player.compensatedEntities.updateAttributes(entityID, attributes.getProperties()));
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_STATUS) {
            WrapperPlayServerEntityStatus status = new WrapperPlayServerEntityStatus(event);
            // This hasn't changed from 1.7.2 to 1.17
            // Needed to exempt players on dead vehicles, as dead entities have strange physics.
            if (status.getStatus() == 3) {
                PacketEntity entity = player.compensatedEntities.getEntity(status.getEntityId());

                if (entity == null) return;
                entity.isDead = true;
            }

            if (status.getStatus() == 9) {
                if (status.getEntityId() != player.entityID) return;

                player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> player.packetStateData.setSlowedByUsingItem(false));
                player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get() + 1, () -> player.packetStateData.setSlowedByUsingItem(false));
            }

            if (status.getStatus() == 31) {
                PacketEntity hook = player.compensatedEntities.getEntity(status.getEntityId());
                if (!(hook instanceof PacketEntityHook hookEntity)) return;

                if (hookEntity.attached == player.entityID) {
                    player.sendTransaction();
                    // We don't transaction sandwich this, it's too rare to be a real problem.
                    player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> player.uncertaintyHandler.fishingRodPulls.add(hookEntity.owner));
                }
            }

            if (status.getStatus() >= 24 && status.getStatus() <= 28 && status.getEntityId() == player.entityID) {
                player.compensatedEntities.self.opLevel = status.getStatus() - 24;
            }
        } else if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
            WrapperPlayServerSetSlot slot = new WrapperPlayServerSetSlot(event);

            if (slot.getWindowId() == 0) {
                Runnable task = () -> {
                    if (slot.getSlot() - 36 == player.packetStateData.lastSlotSelected && (
                            !player.inventory.getHeldItem().is(slot.getItem().getType()) || player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8)
                    ) || slot.getSlot() == 45 && !player.inventory.getOffHand().is(slot.getItem().getType())) {
                        InteractionHand hand = slot.getSlot() == 45 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                        if (hand == player.packetStateData.itemInUseHand) {
                            player.packetStateData.setSlowedByUsingItem(false);
                        }

                        if (player.isResetItemUsageOnItemUpdate() && hand == WatchNekoAPI.INSTANCE.getItemResetHandler().getItemUsageHand(player.platformPlayer)) {
                            WatchNekoAPI.INSTANCE.getItemResetHandler().resetItemUsage(player.platformPlayer);
                        }
                    }
                };

                player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), task);
                player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get() + 1, task);
            }
        } else if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
            WrapperPlayServerWindowItems items = new WrapperPlayServerWindowItems(event);

            if (items.getWindowId() == 0) { // Player inventory
                Runnable task = () -> {
                    if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8)) {
                        player.packetStateData.setSlowedByUsingItem(false);
                        if (player.isResetItemUsageOnItemUpdate()) {
                            WatchNekoAPI.INSTANCE.getItemResetHandler().resetItemUsage(player.platformPlayer);
                        }
                    } else {
                        if (items.getItems().size() > 45 && !player.inventory.getOffHand().is(items.getItems().get(45).getType())) {
                            if (player.packetStateData.itemInUseHand == InteractionHand.OFF_HAND) {
                                player.packetStateData.setSlowedByUsingItem(false);
                            }

                            if (player.isResetItemUsageOnItemUpdate() && WatchNekoAPI.INSTANCE.getItemResetHandler().getItemUsageHand(player.platformPlayer) == InteractionHand.OFF_HAND) {
                                WatchNekoAPI.INSTANCE.getItemResetHandler().resetItemUsage(player.platformPlayer);
                            }
                        }

                        if (!player.inventory.getHeldItem().is(items.getItems().get(player.packetStateData.lastSlotSelected + 36).getType())) {
                            if (player.packetStateData.itemInUseHand == InteractionHand.MAIN_HAND) {
                                player.packetStateData.setSlowedByUsingItem(false);
                            }

                            if (player.isResetItemUsageOnItemUpdate() && WatchNekoAPI.INSTANCE.getItemResetHandler().getItemUsageHand(player.platformPlayer) == InteractionHand.MAIN_HAND) {
                                WatchNekoAPI.INSTANCE.getItemResetHandler().resetItemUsage(player.platformPlayer);
                            }
                        }
                    }
                };

                player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), task);
                player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get() + 1, task);
            }
        }

        // 1.8 clients fail to send the RELEASE_USE_ITEM packet when a window is opened client sided while using an item
        else if (event.getPacketType() == PacketType.Play.Server.OPEN_WINDOW) {
            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> player.packetStateData.setSlowedByUsingItem(false));
            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get() + 1, () -> player.packetStateData.setSlowedByUsingItem(false));
        } else if (event.getPacketType() == PacketType.Play.Server.OPEN_HORSE_WINDOW) {
            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> player.packetStateData.setSlowedByUsingItem(false));
            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get() + 1, () -> player.packetStateData.setSlowedByUsingItem(false));
        } else if (event.getPacketType() == PacketType.Play.Server.SET_PASSENGERS) {
            WrapperPlayServerSetPassengers mount = new WrapperPlayServerSetPassengers(event);

            int vehicleID = mount.getEntityId();
            int[] passengers = mount.getPassengers();

            handleMountVehicle(event, vehicleID, passengers);
        } else if (event.getPacketType() == PacketType.Play.Server.ATTACH_ENTITY) {
            WrapperPlayServerAttachEntity attach = new WrapperPlayServerAttachEntity(event);

            // This packet was replaced by the mount packet on 1.9+ servers - to support multiple passengers on one vehicle
            if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_9))
                return;

            // If this is mounting rather than leashing
            if (!attach.isLeash()) {
                // Alright, let's convert this to the 1.9+ format to make it easier for grim
                int vehicleID = attach.getHoldingId();
                int attachID = attach.getAttachedId();
                TrackerData trackerData = player.compensatedEntities.getTrackedEntity(attachID);

                if (trackerData != null) {
                    // 1.8 sends a vehicle ID of -1 to dismount the entity from its vehicle
                    // This is opposite of the 1.9+ format, which sends the vehicle ID and then an empty array.
                    if (vehicleID == -1) { // Dismounting
                        vehicleID = trackerData.getLegacyPointEightMountedUpon();
                        handleMountVehicle(event, vehicleID, new int[]{}); // The vehicle is empty
                    } else { // Mounting
                        trackerData.setLegacyPointEightMountedUpon(vehicleID);
                        handleMountVehicle(event, vehicleID, new int[]{attachID});
                    }
                } else {
                    // I don't think we can recover from this... warn and move on as this shouldn't happen.
                    LogUtil.warn("Server sent an invalid attach entity packet for entity " + attach.getHoldingId() + " with passenger " + attach.getAttachedId() + "! The client ignores this.");
                }
            }
        } else if (event.getPacketType() == PacketType.Play.Server.DESTROY_ENTITIES) {
            WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(event);

            int[] destroyEntityIds = destroy.getEntityIds();
            FairReach fairReach = player.checkManager.getCheck(FairReach.class);

            for (int entityID : destroyEntityIds) {
                despawnedEntitiesThisTransaction.add(entityID);
                player.compensatedEntities.serverPositionsMap.remove(entityID);
                fairReach.forgetTarget(entityID);
                // Remove the tracked vehicle (handling tracking knockback) if despawned
                if (player.compensatedEntities.serverPlayerVehicle != null && player.compensatedEntities.serverPlayerVehicle == entityID) {
                    player.compensatedEntities.serverPlayerVehicle = null;
                }
            }

            final int destroyTransaction = player.lastTransactionSent.get() + 1;
            scheduleEntityUpdate(event, destroyTransaction, () -> {
                for (int integer : destroyEntityIds) {
                    player.compensatedEntities.removeEntity(integer);
                    player.fireworks.removeFirework(integer);
                    player.compensatedEntities.entitiesRemovedThisTick.add(integer);
                }
            });

            // Don't let the player freeze transactions to keep the firework boost velocity + uncertainty
            // Also generally prevents people with high ping gaining too high an advantage in firework use
            if (maxFireworkBoostPing > 0) {
                player.runNettyTaskInMs(() -> {
                    if (player.lastTransactionReceived.get() >= destroyTransaction) return;
                    for (int entityID : destroyEntityIds) {
                        // If the player has a firework boosting them, setback
                        if (player.fireworks.hasFirework(entityID)) {
                            player.getSetbackTeleportUtil().executeViolationSetback();
                            break;
                        }
                    }
                }, maxFireworkBoostPing);
            }
        }
    }

    private void handleMountVehicle(PacketSendEvent event, int vehicleID, int[] passengers) {
        boolean wasInVehicle = player.compensatedEntities.serverPlayerVehicle != null && player.compensatedEntities.serverPlayerVehicle == vehicleID;
        boolean inThisVehicle = false;

        for (int passenger : passengers) {
            inThisVehicle = passenger == player.entityID;
            if (inThisVehicle) break;
        }

        if (inThisVehicle && !wasInVehicle) {
            player.handleMountVehicle(vehicleID);
        }

        if (!inThisVehicle && wasInVehicle) {
            player.handleDismountVehicle(event);
        }
        // Better lag compensation if we were affected by this
        if (!player.movementSimulation.isUnified() && (wasInVehicle || inThisVehicle)) {
            player.sendTransaction();
        }
        scheduleEntityUpdate(event, player.lastTransactionSent.get(), () -> {
            PacketEntity vehicle = player.compensatedEntities.getEntity(vehicleID);

            // Vanilla likes sending null vehicles, so we must ignore those like the client ignores them
            if (vehicle == null) return;

            // Eject existing passengers for this vehicle
            for (PacketEntity passenger : new ArrayList<>(vehicle.passengers)) {
                player.compensatedEntities.settlePassenger(passenger);
                passenger.eject();
            }

            // Add the entities as vehicles
            for (int entityID : passengers) {
                PacketEntity passenger = player.compensatedEntities.getEntity(entityID);
                if (passenger == null) continue;
                player.compensatedEntities.settlePassenger(passenger);
                passenger.mount(vehicle);
            }
        });
    }

    private void handleMoveEntity(PacketSendEvent event, int entityId, double deltaX, double deltaY, double deltaZ, Float yaw, Float pitch, boolean isRelative, boolean hasPos) {
        TrackerData data = player.compensatedEntities.getTrackedEntity(entityId);

        boolean unified = player.movementSimulation.isUnified();
        if (!unified && hasSentPreWavePacket.compareAndSet(false, true)) player.sendTransaction();

        if (data != null) {
            // Update the tracked server's entity position
            if (isRelative) {
                // There is a bug where vehicles may start flying due to mojang setting packet position on the client
                // (Works at 0 ping but causes funny bugs at any higher ping)
                // As we don't want vehicles to fly, we need to replace it with a teleport if it is player vehicle
                //
                // Don't bother with client controlled vehicles though
                boolean vanillaVehicleFlight = player.compensatedEntities.serverPlayerVehicle != null
                        && player.compensatedEntities.serverPlayerVehicle == entityId
                        && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)
                        // TODO: https://discord.com/channels/721686193061888071/721686193515003966/1310659538831020123
                        // Why does the server now send an entity rel move packet matching the player's vehicle movement every time?
                        && PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_21_2)
                        && PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_9);

                // ViaVersion sends two relative packets when moving more than 4 blocks
                // This is broken and causes the client to interpolate like (0, 4) and (1, 3) instead of (1, 7)
                // This causes impossible hits, so grim must replace this with a teleport entity packet
                // Not ideal, but neither is 1.8 players on a 1.9+ server.
                if (vanillaVehicleFlight ||
                        ((Math.abs(deltaX) >= 3.9375 || Math.abs(deltaY) >= 3.9375 || Math.abs(deltaZ) >= 3.9375) && player.getClientVersion().isOlderThan(ClientVersion.V_1_9) && PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_9))) {
                    player.user.writePacket(new WrapperPlayServerEntityTeleport(entityId, new Vector3d(data.getX() + deltaX, data.getY() + deltaY, data.getZ() + deltaZ), yaw == null ? data.getXRot() : yaw, pitch == null ? data.getYRot() : pitch, false));
                    event.setCancelled(true);
                    return;
                }

                data.setX(data.getX() + deltaX);
                data.setY(data.getY() + deltaY);
                data.setZ(data.getZ() + deltaZ);
            } else {
                data.setX(deltaX);
                data.setY(deltaY);
                data.setZ(deltaZ);
            }
            if (yaw != null) {
                data.setXRot(yaw);
                data.setYRot(pitch);
            }

            // We can't hang two relative moves on one transaction
            if (!unified && data.getLastTransactionHung() == player.lastTransactionSent.get()) {
                player.sendTransaction();
            }
            data.setLastTransactionHung(player.lastTransactionSent.get() + (unified ? 1 : 0));
        }

        int lastTrans = player.lastTransactionSent.get();
        if (hasPos && data != null && data.getEntityType() == EntityTypes.PLAYER) {
            player.checkManager.getCheck(FairReach.class).trackPosition(event, entityId, data, lastTrans + 1, isRelative);
        }

        Runnable apply = () -> {
            PacketEntity entity = player.compensatedEntities.getEntity(entityId);
            if (entity == null) return;
            if (entity instanceof PacketEntityTrackXRot xRotEntity && yaw != null) {
                xRotEntity.packetYaw = yaw;
                xRotEntity.steps = entity.isBoat ? 10 : 3;
            }

            entity.onFirstTransaction(isRelative, hasPos, deltaX, deltaY, deltaZ, player);
        };
        Runnable finish = () -> {
            PacketEntity entity = player.compensatedEntities.getEntity(entityId);
            if (entity == null) return;
            entity.onSecondTransaction();
        };
        if (!unified) {
            player.latencyUtils.addRealTimeTaskWithNextTransaction(lastTrans, apply, finish);
            return;
        }
        PacketEntity current = player.compensatedEntities.getEntity(entityId);
        SimpleCollisionBox pending = hasPos && data != null && current != null
                ? cc.watchneko.utils.nmsutil.GetBoundingBox.getPacketEntityBoundingBox(player, data.getX(), data.getY(), data.getZ(), current)
                : null;
        player.movementReceipts.after(event, () -> {
            if (pending != null) current.sentMovement(pending);
        }, receipt -> {
            if (pending != null) current.receivedMovement(pending);
            apply.run();
            finish.run();
        });
    }

    private void addEntity(PacketSendEvent event, int entityID, UUID uuid, EntityType type, Vector3d position, float xRot, float yRot, List<EntityData<?>> entityMetadata, int extraData) {
        player.checkManager.getCheck(FairReach.class).forgetTarget(entityID);
        if (!player.movementSimulation.isUnified() && despawnedEntitiesThisTransaction.contains(entityID)) {
            player.sendTransaction();
        }

        player.compensatedEntities.serverPositionsMap.put(entityID, new TrackerData(position.getX(), position.getY(), position.getZ(), xRot, yRot, type, player.lastTransactionSent.get()));

        scheduleEntityUpdate(event, player.lastTransactionSent.get(), () -> {
            player.compensatedEntities.addEntity(entityID, uuid, type, position, xRot, extraData);
            if (entityMetadata != null) {
                player.compensatedEntities.updateEntityMetadata(entityID, entityMetadata);
            }
        });
    }

    private void scheduleEntityUpdate(PacketSendEvent event, int legacyTransaction, Runnable update) {
        if (player.movementSimulation.isUnified()) player.movementReceipts.after(event, () -> {}, receipt -> update.run());
        else player.latencyUtils.addRealTimeTask(legacyTransaction, update);
    }

    private boolean isDirectlyAffectingPlayer(PlayerData player, int entityID) {
        // The attributes for this entity is active, currently
        return (player.compensatedEntities.serverPlayerVehicle == null && entityID == player.entityID) ||
                (player.compensatedEntities.serverPlayerVehicle != null && entityID == player.compensatedEntities.serverPlayerVehicle);
    }

    public void onEndOfTickEvent() {
        // Only send a transaction at the end of the tick if we are tracking players
        if (!player.movementSimulation.isUnified()) player.sendTransaction(true);
    }

    public void tickStartTick() {
        if (!player.movementSimulation.isUnified()) hasSentPreWavePacket.set(false);
    }

    @Override
    public void onReload(ConfigManager config) {
        maxFireworkBoostPing = config.getIntElse("max-ping-firework-boost", 1000);
    }

}
