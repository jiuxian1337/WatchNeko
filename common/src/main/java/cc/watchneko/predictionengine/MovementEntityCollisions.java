package cc.watchneko.predictionengine;

import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import cc.watchneko.utils.data.packetentity.PacketEntity;
import cc.watchneko.utils.data.packetentity.PacketEntityShulker;
import cc.watchneko.utils.latency.CompensatedEntities;

import java.util.ArrayList;
import java.util.List;

/** Keeps each entity at one possible location in each collision solve. */
final class MovementEntityCollisions {
    static final int MAX_WORLDS = 16;

    private MovementEntityCollisions() {}

    static List<HardEntity> snapshot(PlayerData player) {
        CompensatedEntities entities = player.compensatedEntities;
        PacketEntity vehicle = player.getVehicle();
        PacketEntity ownRoot = CompensatedEntities.ridingRoot(entities.self);
        List<HardEntity> snapshot = new ArrayList<>();
        for (PacketEntity entity : entities.entityMap.values()) {
            if (entity == vehicle || entity == entities.self || entity.isDead || entities.isUntrackedRider(entity)) continue;
            boolean solid = entity.isBoat || entity instanceof PacketEntityShulker || entity.isHappyGhast;
            if (!solid && !(vehicle != null && vehicle.isBoat && entity.isPushable())) continue;
            if (entity.riding != null && CompensatedEntities.ridingRoot(entity) == ownRoot) continue;

            SimpleCollisionBox envelope = entity.getPossibleCollisionBoxes();
            if (entity instanceof PacketEntityShulker) envelope.expand(envelope.maxX - envelope.minX);
            snapshot.add(new HardEntity(entity, envelope));
        }
        return snapshot;
    }

    static List<List<SimpleCollisionBox>> worlds(List<HardEntity> snapshot, SimpleCollisionBox sweep) {
        List<List<SimpleCollisionBox>> worlds = List.of(List.of());
        for (HardEntity entity : snapshot) {
            if (!entity.envelope.isIntersected(sweep)) continue;
            if (!entity.resolve()) return null;
            worlds = add(worlds, entity.positions, sweep);
            if (worlds == null) return null;
        }
        return worlds;
    }

    static final class HardEntity {
        private final PacketEntity entity;
        private final SimpleCollisionBox envelope;
        private List<SimpleCollisionBox> positions;
        private boolean complete;

        private HardEntity(PacketEntity entity, SimpleCollisionBox envelope) {
            this.entity = entity;
            this.envelope = envelope;
        }

        private boolean resolve() {
            if (positions == null) {
                positions = new ArrayList<>();
                complete = entity.appendMovementBoxes(positions);
            }
            return complete;
        }
    }

    static List<List<SimpleCollisionBox>> add(List<List<SimpleCollisionBox>> worlds,
                                              List<SimpleCollisionBox> positions, SimpleCollisionBox sweep) {
        boolean outside = false;
        List<SimpleCollisionBox> relevant = new ArrayList<>();
        for (SimpleCollisionBox box : positions) {
            if (!box.isIntersected(sweep)) outside = true;
            else if (relevant.stream().noneMatch(existing -> sameBox(existing, box))) relevant.add(box);
        }
        if (relevant.isEmpty()) return worlds;
        int count = worlds.size() * (relevant.size() + (outside ? 1 : 0));
        if (count > MAX_WORLDS) return null;

        List<List<SimpleCollisionBox>> result = new ArrayList<>(count);
        for (List<SimpleCollisionBox> world : worlds) {
            if (outside) result.add(world);
            for (SimpleCollisionBox box : relevant) {
                List<SimpleCollisionBox> variant = new ArrayList<>(world.size() + 1);
                variant.addAll(world);
                variant.add(box);
                result.add(variant);
            }
        }
        return result;
    }

    private static boolean sameBox(SimpleCollisionBox first, SimpleCollisionBox second) {
        return first.minX == second.minX && first.minY == second.minY && first.minZ == second.minZ
                && first.maxX == second.maxX && first.maxY == second.maxY && first.maxZ == second.maxZ;
    }
}
