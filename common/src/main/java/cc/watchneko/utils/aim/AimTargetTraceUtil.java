package cc.watchneko.utils.aim;

import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import cc.watchneko.utils.data.packetentity.PacketEntity;
import cc.watchneko.utils.math.MathUtil;
import cc.watchneko.utils.math.Vec2f;

public final class AimTargetTraceUtil {
    private AimTargetTraceUtil() {
    }

    public static PacketEntity getPlayerTarget(PlayerData player) {
        PacketEntity target = player.getTarget();
        return target != null && target.canHit() ? target : null;
    }

    public static SimpleCollisionBox getTargetBox(PacketEntity target) {
        return target.getPossibleCollisionBoxes().copy();
    }

    public static double centerX(SimpleCollisionBox box) {
        return (box.minX + box.maxX) * 0.5;
    }

    public static double centerY(SimpleCollisionBox box) {
        return (box.minY + box.maxY) * 0.5;
    }

    public static double centerZ(SimpleCollisionBox box) {
        return (box.minZ + box.maxZ) * 0.5;
    }

    public static double horizontalCenterDistance(PlayerData player, SimpleCollisionBox box) {
        double dx = centerX(box) - player.x;
        double dz = centerZ(box) - player.z;
        return Math.hypot(dx, dz);
    }

    public static double centerDistance(SimpleCollisionBox first, SimpleCollisionBox second) {
        double dx = centerX(first) - centerX(second);
        double dy = centerY(first) - centerY(second);
        double dz = centerZ(first) - centerZ(second);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static double directionToCenter(PlayerData player, SimpleCollisionBox box) {
        double x = centerX(box);
        double z = centerZ(box);
        return Math.toDegrees(Math.atan2(z - player.z, x - player.x)) - 90.0;
    }

    public static float angleToCenter(PlayerData player, float yaw, SimpleCollisionBox box) {
        return Math.abs(MathUtil.getAngleDifference(yaw, (float) directionToCenter(player, box)));
    }

    public static Vec2f genericRotations(double x, double y, double z, SimpleCollisionBox box) {
        double diffX = centerX(box) + 0.1 - x;
        double diffY = box.minY - 2.2 + 1.62 - y;
        double diffZ = centerZ(box) + 0.1 - z;
        double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
        float yaw = (float) (Math.atan2(diffZ, diffX) * 180.0 / Math.PI) - 90.0F;
        float pitch = (float) (-(Math.atan2(diffY, dist) * 180.0 / Math.PI));
        double pitchToEntity = -Math.toDegrees(Math.atan(diffY / dist));
        pitch += -MathUtil.wrapAngleTo180_float(pitch - (float) pitchToEntity) - 2.5F;
        return new Vec2f(yaw, pitch);
    }

    public static Vec2f commonRotations(double x, double y, double z, SimpleCollisionBox box) {
        double xDiff = centerX(box) - x;
        double zDiff = centerZ(box) - z;
        double yDiff = box.minY - (y + 1.62);
        double dist = Math.sqrt(xDiff * xDiff + zDiff * zDiff);
        float yaw = (float) (Math.atan2(zDiff, xDiff) * 180.0 / Math.PI) - 90.0F;
        float pitch = (float) (-(Math.atan2(yDiff, dist) * 180.0 / Math.PI));
        return new Vec2f(yaw, pitch);
    }

    public static Vec2f predictiveRotations(double x, double y, double z, SimpleCollisionBox current, SimpleCollisionBox previous) {
        double diffX = centerX(current) + (centerX(current) - centerX(previous)) + x;
        double diffY = current.minY - 3.5 + 1.62 - y + 1.62;
        double diffZ = centerZ(current) + (centerZ(current) - centerZ(previous)) + z;
        double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
        float yaw = (float) Math.toDegrees(-Math.atan(diffX / diffZ));
        float pitch = (float) (-Math.toDegrees(Math.atan(diffY / dist)));
        if (diffX < 0.0 && diffZ < 0.0) {
            yaw = (float) (90.0 + Math.toDegrees(Math.atan(diffZ / diffX)));
        } else if (diffX > 0.0 && diffZ < 0.0) {
            yaw = (float) (-90.0 + Math.toDegrees(Math.atan(diffZ / diffX)));
        }
        return new Vec2f(yaw, pitch);
    }

    public static Vec2f offsetRotations(double x, double y, double z, float currentYaw, float currentPitch, SimpleCollisionBox box) {
        double diffX = centerX(box) - x;
        double diffY = box.minY + 1.4580000000000002 - (y + 1.62);
        double diffZ = centerZ(box) - z;
        double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
        float yaw = (float) (Math.atan2(diffZ, diffX) * 180.0 / Math.PI) - 90.0F;
        float pitch = (float) (-(Math.atan2(diffY, dist) * 180.0 / Math.PI));
        return new Vec2f(
                currentYaw + MathUtil.wrapAngleTo180_float(yaw - currentYaw),
                currentPitch + MathUtil.wrapAngleTo180_float(pitch - currentPitch) + 4.0F
        );
    }
}
