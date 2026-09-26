package cc.watchneko.manager;

import ac.grim.grimac.api.AbstractCheck;
import cc.watchneko.WatchNekoAPI;
import cc.watchneko.checks.impl.aim.*;
import cc.watchneko.checks.impl.aim.processor.AimProcessor;
import cc.watchneko.checks.impl.aim.processor.Cinematic;
import cc.watchneko.checks.impl.autoclicker.AutoclickerA;
import cc.watchneko.checks.impl.badpackets.*;
import cc.watchneko.checks.impl.breaking.*;
import cc.watchneko.checks.impl.chat.ChatA;
import cc.watchneko.checks.impl.chat.ChatB;
import cc.watchneko.checks.impl.chat.ChatC;
import cc.watchneko.checks.impl.chat.ChatD;
import cc.watchneko.checks.impl.combat.Hitboxes;
import cc.watchneko.checks.impl.combat.MultiInteractA;
import cc.watchneko.checks.impl.combat.MultiInteractB;
import cc.watchneko.checks.impl.combat.Reach;
import cc.watchneko.checks.impl.crash.*;
import cc.watchneko.checks.impl.elytra.*;
import cc.watchneko.checks.impl.exploit.ExploitA;
import cc.watchneko.checks.impl.exploit.ExploitB;
import cc.watchneko.checks.impl.groundspoof.NoFallA;
import cc.watchneko.checks.impl.groundspoof.NoFallB;
import cc.watchneko.checks.impl.interact.InteractA;
import cc.watchneko.checks.impl.interact.InteractB;
import cc.watchneko.checks.impl.inventory.*;
import cc.watchneko.checks.impl.misc.ClientBrand;
import cc.watchneko.checks.impl.misc.GhostBlockMitigation;
import cc.watchneko.checks.impl.misc.Post;
import cc.watchneko.checks.impl.misc.TransactionOrder;
import cc.watchneko.checks.impl.movement.*;
import cc.watchneko.checks.impl.multiactions.*;
import cc.watchneko.checks.impl.packetorder.*;
import cc.watchneko.checks.impl.pingspoof.PingSpoofA;
import cc.watchneko.checks.impl.pingspoof.PingSpoofB;
import cc.watchneko.checks.impl.pingspoof.PingSpoofC;
import cc.watchneko.checks.impl.pingspoof.PingSpoofD;
import cc.watchneko.checks.impl.prediction.DebugHandler;
import cc.watchneko.checks.impl.prediction.GroundSpoof;
import cc.watchneko.checks.impl.prediction.OffsetHandler;
import cc.watchneko.checks.impl.prediction.Phase;
import cc.watchneko.checks.impl.scaffolding.*;
import cc.watchneko.checks.impl.sprint.*;
import cc.watchneko.checks.impl.stuck.StuckA;
import cc.watchneko.checks.impl.timer.*;
import cc.watchneko.checks.impl.vehicle.*;
import cc.watchneko.checks.impl.velocity.VelocityA;
import cc.watchneko.checks.impl.velocity.VelocityB;
import cc.watchneko.checks.impl.velocity.VelocityC;
import cc.watchneko.checks.type.*;
import cc.watchneko.events.packets.PacketChangeGameState;
import cc.watchneko.events.packets.PacketEntityReplication;
import cc.watchneko.events.packets.PacketPlayerAbilities;
import cc.watchneko.events.packets.PacketWorldBorder;
import cc.watchneko.manager.init.start.SuperDebug;
import cc.watchneko.platform.api.permissions.PermissionDefaultValue;
import cc.watchneko.player.PlayerData;
import cc.watchneko.predictionengine.GhostBlockDetector;
import cc.watchneko.predictionengine.SneakingEstimator;
import cc.watchneko.utils.anticheat.update.*;
import cc.watchneko.utils.latency.CompensatedCooldown;
import cc.watchneko.utils.latency.CompensatedFireworks;
import cc.watchneko.utils.latency.CompensatedInventory;
import cc.watchneko.utils.team.TeamHandler;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.google.common.collect.ClassToInstanceMap;
import com.google.common.collect.ImmutableClassToInstanceMap;

import java.util.concurrent.atomic.AtomicBoolean;

public class CheckManager {
    private static final AtomicBoolean initedAtomic = new AtomicBoolean(false);
    private static boolean inited;
    public final ClassToInstanceMap<AbstractCheck> allChecks;
    private final ClassToInstanceMap<PacketCheck> packetChecks;
    private final ClassToInstanceMap<PositionCheck> positionChecks;
    private final ClassToInstanceMap<RotationCheck> rotationChecks;
    private final ClassToInstanceMap<VehicleCheck> vehicleChecks;
    private final ClassToInstanceMap<PacketCheck> prePredictionChecks;
    private final ClassToInstanceMap<BlockBreakCheck> blockBreakChecks;
    private final ClassToInstanceMap<BlockPlaceCheck> blockPlaceChecks;
    private final ClassToInstanceMap<PostPredictionCheck> postPredictionChecks;
    private PacketEntityReplication packetEntityReplication = null;

    public CheckManager(PlayerData player) {
        packetChecks = new ImmutableClassToInstanceMap.Builder<PacketCheck>()
                .put(PacketOrderProcessor.class, player.packetOrderProcessor)
                .put(Reach.class, new Reach(player))
                .put(InteractB.class, new InteractB(player))
                .put(AutoclickerA.class, new AutoclickerA(player))
                .put(PingSpoofA.class, new PingSpoofA(player))
                .put(PingSpoofB.class, new PingSpoofB(player))
                .put(PingSpoofC.class, new PingSpoofC(player))
                .put(PingSpoofD.class, new PingSpoofD(player))
                .put(StuckA.class, new StuckA(player))
                .put(InventoryA.class, new InventoryA(player))
                .put(InventoryB.class, new InventoryB(player))
                .put(InventoryC.class, new InventoryC(player))
                .put(InventoryD.class, new InventoryD(player))
                .put(InventoryE.class, new InventoryE(player))
                .put(InventoryF.class, new InventoryF(player))
                .put(InventoryG.class, new InventoryG(player))
                .put(PacketEntityReplication.class, new PacketEntityReplication(player))
                .put(PacketChangeGameState.class, new PacketChangeGameState(player))
                .put(CompensatedInventory.class, player.inventory)
                .put(PacketPlayerAbilities.class, new PacketPlayerAbilities(player))
                .put(PacketWorldBorder.class, new PacketWorldBorder(player))
                .put(ActionManager.class, player.actionManager)
                .put(TeamHandler.class, new TeamHandler(player))
                .put(ClientBrand.class, new ClientBrand(player))
                .put(AimW.class, new AimW(player))
                .put(NoFallA.class, new NoFallA(player))
                .put(ChatA.class, new ChatA(player))
                .put(ChatB.class, new ChatB(player))
                .put(ChatC.class, new ChatC(player))
                .put(ChatD.class, new ChatD(player))
                .put(ExploitA.class, new ExploitA(player))
                .put(ExploitB.class, new ExploitB(player))
                .put(BadPacketsA.class, new BadPacketsA(player))
                .put(BadPacketsB.class, new BadPacketsB(player))
                .put(BadPacketsC.class, new BadPacketsC(player))
                .put(BadPacketsD.class, new BadPacketsD(player))
                .put(BadPacketsE.class, new BadPacketsE(player))
                .put(BadPacketsF.class, new BadPacketsF(player))
                .put(BadPacketsG.class, new BadPacketsG(player))
                .put(BadPacketsI.class, new BadPacketsI(player))
                .put(BadPacketsJ.class, new BadPacketsJ(player))
                .put(BadPacketsK.class, new BadPacketsK(player))
                .put(BadPacketsL.class, new BadPacketsL(player))
                .put(BadPacketsM.class, new BadPacketsM(player))
                .put(BadPacketsO.class, new BadPacketsO(player))
                .put(BadPacketsP.class, new BadPacketsP(player))
                .put(BadPacketsQ.class, new BadPacketsQ(player))
                .put(BadPacketsR.class, new BadPacketsR(player))
                .put(BadPacketsS.class, new BadPacketsS(player))
                .put(BadPacketsT.class, new BadPacketsT(player))
                .put(BadPacketsU.class, new BadPacketsU(player))
                .put(BadPacketsV.class, new BadPacketsV(player))
                .put(BadPacketsY.class, new BadPacketsY(player))
                .put(MultiActionsA.class, new MultiActionsA(player))
                .put(MultiActionsC.class, new MultiActionsC(player))
                .put(MultiActionsD.class, new MultiActionsD(player))
                .put(MultiActionsE.class, new MultiActionsE(player))
                .put(PacketOrderB.class, new PacketOrderB(player))
                .put(PacketOrderC.class, new PacketOrderC(player))
                .put(PacketOrderD.class, new PacketOrderD(player))
                .put(PacketOrderO.class, new PacketOrderO(player))
                .put(SprintA.class, new SprintA(player))
                .put(VehicleA.class, new VehicleA(player))
                .put(VehicleB.class, new VehicleB(player))
                .put(VehicleD.class, new VehicleD(player))
                .put(VehicleE.class, new VehicleE(player))
                .put(VehicleF.class, new VehicleF(player))
                .put(CrashB.class, new CrashB(player))
                .put(CrashD.class, new CrashD(player))
                .put(CrashE.class, new CrashE(player))
                .put(CrashF.class, new CrashF(player))
                .put(CrashH.class, new CrashH(player))
                .put(CrashI.class, new CrashI(player))
                .put(SetbackBlocker.class, new SetbackBlocker(player)) // Must be last class otherwise we can't check while blocking packets
                .build();

        positionChecks = new ImmutableClassToInstanceMap.Builder<PositionCheck>()
                .put(PredictionRunner.class, new PredictionRunner(player))
                .put(CompensatedCooldown.class, new CompensatedCooldown(player))
                .build();
        rotationChecks = new ImmutableClassToInstanceMap.Builder<RotationCheck>()
                .put(AimProcessor.class, new AimProcessor(player))
                .put(Cinematic.class, new Cinematic(player))
                .put(AimP.class, new AimP(player))
                .put(AimH.class, new AimH(player))
                .put(AimM.class, new AimM(player))
                .put(AimF.class, new AimF(player))
                .put(AimO.class, new AimO(player))
                .put(AimB.class, new AimB(player))
                .put(AimS.class, new AimS(player))
                .put(AimX.class, new AimX(player))
                .put(AimL.class, new AimL(player))
                .put(AimT.class, new AimT(player))
                .put(AimC.class, new AimC(player))
                .put(AimN.class, new AimN(player))
                .put(AimV.class, new AimV(player))
                .put(AimA.class, new AimA(player))
                .put(AimR.class, new AimR(player))
                .put(AimQ.class, new AimQ(player))
                .put(AimG.class, new AimG(player))
                .put(AimK.class, new AimK(player))
                .put(AimJ.class, new AimJ(player))
                .put(AimD.class, new AimD(player))
                .put(AimE.class, new AimE(player))
                .put(AimI.class, new AimI(player))
                .put(AimY.class, new AimY(player))
                .put(AimZ.class, new AimZ(player))
                .put(AimAA.class, new AimAA(player))
                .put(AimAB.class, new AimAB(player))
                .build();
        vehicleChecks = new ImmutableClassToInstanceMap.Builder<VehicleCheck>()
                .put(VehiclePredictionRunner.class, new VehiclePredictionRunner(player))
                .build();

        postPredictionChecks = new ImmutableClassToInstanceMap.Builder<PostPredictionCheck>()
                .put(NoFallB.class, new NoFallB(player))
                .put(NegativeTimer.class, new NegativeTimer(player))
                .put(VelocityB.class, new VelocityB(player))
                .put(VelocityA.class, new VelocityA(player))
                .put(VelocityC.class, new VelocityC(player))
                .put(GhostBlockDetector.class, new GhostBlockDetector(player))
                .put(Phase.class, new Phase(player))
                .put(Post.class, new Post(player))
                .put(PacketOrderA.class, new PacketOrderA(player))
                .put(PacketOrderE.class, new PacketOrderE(player))
                .put(PacketOrderF.class, new PacketOrderF(player))
                .put(PacketOrderG.class, new PacketOrderG(player))
                .put(PacketOrderH.class, new PacketOrderH(player))
                .put(PacketOrderI.class, new PacketOrderI(player))
                .put(PacketOrderJ.class, new PacketOrderJ(player))
                .put(PacketOrderK.class, new PacketOrderK(player))
                .put(PacketOrderL.class, new PacketOrderL(player))
                .put(PacketOrderM.class, new PacketOrderM(player))
                .put(GroundSpoof.class, new GroundSpoof(player))
                .put(OffsetHandler.class, new OffsetHandler(player))
                .put(SuperDebug.class, new SuperDebug(player))
                .put(DebugHandler.class, new DebugHandler(player))
                .put(BadPacketsX.class, new BadPacketsX(player))
                .put(NoSlowA.class, new NoSlowA(player))
                .put(NoSlowB.class, new NoSlowB(player))
                .put(SprintB.class, new SprintB(player))
                .put(SprintC.class, new SprintC(player))
                .put(SprintD.class, new SprintD(player))
                .put(SprintE.class, new SprintE(player))
                .put(SprintF.class, new SprintF(player))
                .put(SprintG.class, new SprintG(player))
                .put(MultiInteractA.class, new MultiInteractA(player))
                .put(MultiInteractB.class, new MultiInteractB(player))
                .put(ElytraA.class, new ElytraA(player))
                .put(ElytraB.class, new ElytraB(player))
                .put(ElytraC.class, new ElytraC(player))
                .put(ElytraD.class, new ElytraD(player))
                .put(ElytraE.class, new ElytraE(player))
                .put(ElytraF.class, new ElytraF(player))
                .put(ElytraG.class, new ElytraG(player))
                .put(ElytraH.class, new ElytraH(player))
                .put(ElytraI.class, new ElytraI(player))
                .put(SetbackTeleportUtil.class, new SetbackTeleportUtil(player)) // Avoid teleporting to new position, update safe pos last
                .put(CompensatedFireworks.class, player.fireworks)
                .put(SneakingEstimator.class, new SneakingEstimator(player))
                .put(LastInstanceManager.class, player.lastInstanceManager)
                .build();

        blockPlaceChecks = new ImmutableClassToInstanceMap.Builder<BlockPlaceCheck>()
                .put(InvalidPlaceA.class, new InvalidPlaceA(player))
                .put(InvalidPlaceB.class, new InvalidPlaceB(player))
                .put(AirLiquidPlace.class, new AirLiquidPlace(player))
                .put(MultiPlace.class, new MultiPlace(player))
                .put(MultiActionsF.class, new MultiActionsF(player))
                .put(MultiActionsG.class, new MultiActionsG(player))
                .put(BadPacketsH.class, new BadPacketsH(player))
                .put(CrashG.class, new CrashG(player))
                .put(FarPlace.class, new FarPlace(player))
                .put(FabricatedPlace.class, new FabricatedPlace(player))
                .put(PositionPlace.class, new PositionPlace(player))
                .put(RotationPlace.class, new RotationPlace(player))
                .put(InteractA.class, new InteractA(player))
                .put(ScaffoldA.class, new ScaffoldA(player))
                .put(ScaffoldB.class, new ScaffoldB(player))
                .put(ScaffoldC.class, new ScaffoldC(player))
                .put(ScaffoldD.class, new ScaffoldD(player))
                .put(ScaffoldProcessor.class, new ScaffoldProcessor(player))
                .put(PacketOrderN.class, new PacketOrderN(player))
                .put(DuplicateRotPlace.class, new DuplicateRotPlace(player))
                .put(GhostBlockMitigation.class, new GhostBlockMitigation(player))
                .build();

        prePredictionChecks = new ImmutableClassToInstanceMap.Builder<PacketCheck>()
                .put(Timer.class, new Timer(player))
                .put(TickTimer.class, new TickTimer(player))
                .put(TimerLimit.class, new TimerLimit(player))
                .put(CrashA.class, new CrashA(player))
                .put(CrashC.class, new CrashC(player))
                .put(VehicleTimer.class, new VehicleTimer(player))
                .build();

        blockBreakChecks = new ImmutableClassToInstanceMap.Builder<BlockBreakCheck>()
                .put(AirLiquidBreak.class, new AirLiquidBreak(player))
                .put(WrongBreak.class, new WrongBreak(player))
                .put(RotationBreak.class, new RotationBreak(player))
                .put(FastBreak.class, new FastBreak(player))
                .put(MultiBreak.class, new MultiBreak(player))
                .put(NoSwingBreak.class, new NoSwingBreak(player))
                .put(FarBreak.class, new FarBreak(player))
                .put(InvalidBreak.class, new InvalidBreak(player))
                .put(PositionBreakA.class, new PositionBreakA(player))
                .put(PositionBreakB.class, new PositionBreakB(player))
                .put(MultiActionsB.class, new MultiActionsB(player))
                .build();

        // All checks that have no listeners, generally invoked by other code to flag
        // TODO migrate more checks to here
        ClassToInstanceMap<AbstractCheck> noneModules = new ImmutableClassToInstanceMap.Builder<AbstractCheck>()
                // BadPacketsN/W + VehicleC + TransactionOrder are packet checks with no listener
                .put(BadPacketsN.class, new BadPacketsN(player))
                .put(BadPacketsW.class, new BadPacketsW(player))
                .put(TransactionOrder.class, new TransactionOrder(player))
                .put(VehicleC.class, new VehicleC(player))
                .put(Hitboxes.class, new Hitboxes(player)) // Hitboxes is invoked by Reach
                .build();

        allChecks = new ImmutableClassToInstanceMap.Builder<AbstractCheck>()
                .putAll(packetChecks)
                .putAll(positionChecks)
                .putAll(rotationChecks)
                .putAll(vehicleChecks)
                .putAll(postPredictionChecks)
                .putAll(blockPlaceChecks)
                .putAll(prePredictionChecks)
                .putAll(blockBreakChecks)
                .putAll(noneModules)
                .build();

        init();
    }

    @SuppressWarnings("unchecked")
    public <T extends AbstractCheck> T getCheck(Class<T> check) {
        return (T) allChecks.get(check);
    }

    @SuppressWarnings("unchecked")
    public <T extends PositionCheck> T getPositionCheck(Class<T> check) {
        return (T) positionChecks.get(check);
    }

    @SuppressWarnings("unchecked")
    public <T extends RotationCheck> T getRotationCheck(Class<T> check) {
        return (T) rotationChecks.get(check);
    }

    @SuppressWarnings("unchecked")
    public <T extends BlockPlaceCheck> T getBlockPlaceCheck(Class<T> check) {
        return (T) blockPlaceChecks.get(check);
    }

    public void onPrePredictionReceivePacket(final PacketReceiveEvent packet) {
        for (PacketCheck check : prePredictionChecks.values()) {
            check.onPacketReceive(packet);
        }
    }

    public void onPacketReceive(final PacketReceiveEvent packet) {
        for (PacketCheck check : packetChecks.values()) {
            check.onPacketReceive(packet);
        }
        for (PostPredictionCheck check : postPredictionChecks.values()) {
            check.onPacketReceive(packet);
        }
        for (BlockPlaceCheck check : blockPlaceChecks.values()) {
            check.onPacketReceive(packet);
        }
        for (BlockBreakCheck check : blockBreakChecks.values()) {
            check.onPacketReceive(packet);
        }
        for (RotationCheck check : rotationChecks.values()) {
            check.onPacketReceive(packet);
        }
    }

    public void onPacketSend(final PacketSendEvent packet) {
        for (PacketCheck check : prePredictionChecks.values()) {
            check.onPacketSend(packet);
        }
        for (PacketCheck check : packetChecks.values()) {
            check.onPacketSend(packet);
        }
        for (PostPredictionCheck check : postPredictionChecks.values()) {
            check.onPacketSend(packet);
        }
        for (BlockPlaceCheck check : blockPlaceChecks.values()) {
            check.onPacketSend(packet);
        }
        for (BlockBreakCheck check : blockBreakChecks.values()) {
            check.onPacketSend(packet);
        }
        for (RotationCheck check : rotationChecks.values()) {
            check.onPacketSend(packet);
        }
    }

    public void onPositionUpdate(final PositionUpdate position) {
        for (PositionCheck check : positionChecks.values()) {
            check.onPositionUpdate(position);
        }
    }

    public void onRotationUpdate(final RotationUpdate rotation) {
        for (RotationCheck check : rotationChecks.values()) {
            check.process(rotation);
        }
        for (BlockPlaceCheck check : blockPlaceChecks.values()) {
            check.process(rotation);
        }
    }

    public void onVehiclePositionUpdate(final VehiclePositionUpdate update) {
        for (VehicleCheck check : vehicleChecks.values()) {
            check.process(update);
        }
    }

    public void onPredictionFinish(final PredictionComplete complete) {
        for (PostPredictionCheck check : postPredictionChecks.values()) {
            check.onPredictionComplete(complete);
        }
        for (BlockPlaceCheck check : blockPlaceChecks.values()) {
            check.onPredictionComplete(complete);
        }
        for (BlockBreakCheck check : blockBreakChecks.values()) {
            check.onPredictionComplete(complete);
        }
    }

    public void onBlockPlace(final BlockPlace place) {
        for (BlockPlaceCheck check : blockPlaceChecks.values()) {
            check.onBlockPlace(place);
        }
    }

    public void onPostFlyingBlockPlace(final BlockPlace place) {
        for (BlockPlaceCheck check : blockPlaceChecks.values()) {
            check.onPostFlyingBlockPlace(place);
        }
    }

    public void onBlockBreak(final BlockBreak blockBreak) {
        for (BlockBreakCheck check : blockBreakChecks.values()) {
            check.onBlockBreak(blockBreak);
        }
        for (BlockPlaceCheck check : blockPlaceChecks.values()) {
            check.onBlockBreak(blockBreak);
        }
    }

    public void onPostFlyingBlockBreak(final BlockBreak blockBreak) {
        for (BlockBreakCheck check : blockBreakChecks.values()) {
            check.onPostFlyingBlockBreak(blockBreak);
        }
        for (BlockPlaceCheck check : blockPlaceChecks.values()) {
            check.onPostFlyingBlockBreak(blockBreak);
        }
    }

    public VelocityB getExplosionHandler() {
        return getPostPredictionCheck(VelocityB.class);
    }

    @SuppressWarnings("unchecked")
    public <T extends PacketCheck> T getPacketCheck(Class<T> check) {
        return (T) packetChecks.get(check);
    }

    @SuppressWarnings("unchecked")
    public <T extends PacketCheck> T getPrePredictionCheck(Class<T> check) {
        return (T) prePredictionChecks.get(check);
    }

    public PacketEntityReplication getEntityReplication() {
        if (packetEntityReplication == null)
            packetEntityReplication = getPacketCheck(PacketEntityReplication.class);
        return packetEntityReplication;
    }

    public NoFallA getNoFall() {
        return getPacketCheck(NoFallA.class);
    }

    public VelocityA getKnockbackHandler() {
        return getPostPredictionCheck(VelocityA.class);
    }

    public CompensatedCooldown getCompensatedCooldown() {
        return getPositionCheck(CompensatedCooldown.class);
    }

    public NoSlowA getNoSlow() {
        return getPostPredictionCheck(NoSlowA.class);
    }

    public SetbackTeleportUtil getSetbackUtil() {
        return getPostPredictionCheck(SetbackTeleportUtil.class);
    }

    public DebugHandler getDebugHandler() {
        return getPostPredictionCheck(DebugHandler.class);
    }

    public OffsetHandler getOffsetHandler() {
        return getPostPredictionCheck(OffsetHandler.class);
    }

    @SuppressWarnings("unchecked")
    public <T extends PostPredictionCheck> T getPostPredictionCheck(Class<T> check) {
        return (T) postPredictionChecks.get(check);
    }

    private void init() {
        if (inited || initedAtomic.getAndSet(true)) return;
        inited = true;

        final String[] permissions = {
                "watchneko.exempt.",
                "watchneko.nosetback.",
                "watchneko.nomodifypacket.",
        };

        for (final AbstractCheck check : allChecks.values()) {
            if (check.getConfigName() == null) continue;
            final String id = check.getConfigName().toLowerCase();
            for (String permissionName : permissions) {
                permissionName += id;
                WatchNekoAPI.INSTANCE.getPermissionManager().registerPermission(permissionName, PermissionDefaultValue.FALSE);
            }
        }
    }
}
