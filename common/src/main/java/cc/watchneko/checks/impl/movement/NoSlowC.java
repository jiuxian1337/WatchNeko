package cc.watchneko.checks.impl.movement;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PostPredictionCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.PredictionComplete;
import cc.watchneko.utils.data.VectorData;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;

import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

@CheckData(name = "NoSlowC", description = "Fits different item slowdown states during one use", experimental = true, setback = -1)
public final class NoSlowC extends Check implements PostPredictionCheck {
    private volatile Settings settings;

    private Settings useSettings;
    private ItemType useItem;
    private InteractionHand useHand;
    private long useRevision;
    private long reportedRevision = Long.MIN_VALUE;
    private long useTeleport;
    private final UseEvidence evidence = new UseEvidence();
    private Prediction pending;

    public NoSlowC(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        double fit = config.getDoubleElse("NoSlowC.fit-distance", 0.003);
        double reject = config.getDoubleElse("NoSlowC.reject-distance", 0.012);
        if (!(fit > 0 && reject > fit && Double.isFinite(reject))) {
            fit = 0.003;
            reject = 0.012;
        }
        settings = new Settings(
                config.getBooleanElse("NoSlowC.enabled", false),
                fit, reject,
                Math.max(1, Math.min(40, config.getIntElse("NoSlowC.required-unslowed-ticks", 4))),
                Math.max(0, Math.min(256, config.getIntElse("NoSlowC.max-additional-candidates", 64)))
        );
    }

    public Prediction beginPrediction() {
        pending = null;
        Settings current = settings;
        InteractionHand hand = player.packetStateData.itemInUseHand;
        if (!current.enabled || !isEnabled() || !player.isExperimentalChecks() || player.disableGrim
                || isExemptPermission() || !player.packetStateData.isSlowedByUsingItem()
                || player.inVehicle() || player.isGliding || player.isClimbing
                || player.wasTouchingWater || player.wasTouchingLava
                || player.firstBreadKB != null || player.likelyKB != null
                || player.firstBreadExplosion != null || player.likelyExplosions != null
                || player.packetStateData.tryingToRiptide || player.getSetbackTeleportUtil().blockOffsets
                || !player.isTickingReliablyFor(3) || player.uncertaintyHandler.lastTeleportTicks.hasOccurredSince(1)
                || hand == InteractionHand.MAIN_HAND
                        && player.packetStateData.lastSlotSelected != player.packetStateData.getSlowedByUsingItemSlot()) {
            resetUse();
            return null;
        }

        ItemStack stack = player.inventory.getItemInHand(hand);
        if (stack == null || stack.isEmpty()) {
            resetUse();
            return null;
        }

        long revision = player.packetStateData.getItemUseRevision();
        if (useSettings != current || useRevision != revision || useHand != hand
                || useItem != stack.getType() || useTeleport != player.lastServerTeleport) {
            resetUse();
            useSettings = current;
            useRevision = revision;
            useHand = hand;
            useItem = stack.getType();
            useTeleport = player.lastServerTeleport;
            // The first movement after a state transition can still reflect the previous input.
            return null;
        }

        if (reportedRevision != revision && !evidence.reported) {
            pending = new Prediction(current.fit, current.reject, current.maxAdditionalCandidates);
        }
        return pending;
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        Prediction prediction = pending;
        pending = null;
        if (!complete.isChecked() || player.getSetbackTeleportUtil().blockOffsets
                || !player.packetStateData.isSlowedByUsingItem()
                || useRevision != player.packetStateData.getItemUseRevision()
                || useSettings != settings || useTeleport != player.lastServerTeleport) {
            resetUse();
            return;
        }
        if (prediction != null && prediction.excludedGroup != null
                && evidence.add(prediction.excludedGroup == ItemModel.SLOWED, useSettings.requiredUnslowedTicks)) {
            if (flagAndAlert(String.format(Locale.ROOT, "slowed=%.5f, unslowed=%.5f, proof=%d",
                    prediction.slowed, prediction.unslowed, evidence.unslowedTicks))) {
                reportedRevision = useRevision;
            }
        }
    }

    private void resetUse() {
        useSettings = null;
        useItem = null;
        useHand = null;
        pending = null;
        evidence.reset();
    }

    private record Settings(boolean enabled, double fit, double reject, int requiredUnslowedTicks,
                            int maxAdditionalCandidates) {
    }

    enum ItemModel {
        SLOWED,
        UNSLOWED
    }

    public static final class Prediction {
        private final BitSet measured = new BitSet();
        private final double fit;
        private final double reject;
        private final int maxAdditionalCandidates;
        private double slowed = Double.POSITIVE_INFINITY;
        private double unslowed = Double.POSITIVE_INFINITY;
        private ItemModel excludedGroup;

        Prediction(double fit, double reject, int maxAdditionalCandidates) {
            this.fit = fit;
            this.reject = reject;
            this.maxAdditionalCandidates = maxAdditionalCandidates;
        }

        public void measure(int index, boolean flippedItem, double residual) {
            if (!Double.isFinite(residual)) {
                return;
            }
            measured.set(index);
            if (flippedItem) {
                unslowed = Math.min(unslowed, residual);
            } else {
                slowed = Math.min(slowed, residual);
            }
        }

        ItemModel groupToExclude() {
            if (slowed <= fit && unslowed > reject) {
                return ItemModel.UNSLOWED;
            }
            if (unslowed <= fit && slowed > reject) {
                return ItemModel.SLOWED;
            }
            return null;
        }

        public boolean needsCompletion() {
            return groupToExclude() != null;
        }

        public void complete(List<VectorData> candidates, ToDoubleFunction<VectorData> measureResidual) {
            ItemModel group = groupToExclude();
            if (group == null) {
                return;
            }
            boolean flipped = group == ItemModel.UNSLOWED;
            int remaining = maxAdditionalCandidates;
            // A fit can be proven by one candidate. Exclusion requires checking the whole group.
            for (int index = measured.nextClearBit(0); index < candidates.size(); index = measured.nextClearBit(index + 1)) {
                VectorData candidate = candidates.get(index);
                if (candidate.isFlipItem() != flipped) {
                    continue;
                }
                if (remaining == 0) {
                    return;
                }
                remaining--;
                double residual = measureResidual.applyAsDouble(candidate);
                if (!Double.isFinite(residual)) {
                    return;
                }
                measure(index, flipped, residual);
                if (residual <= reject) {
                    return;
                }
            }
            if (Double.isFinite(flipped ? unslowed : slowed)) {
                excludedGroup = group;
            }
        }

        ItemModel excludedGroup() {
            return excludedGroup;
        }
    }

    static final class UseEvidence {
        private boolean sawSlowed;
        private int unslowedTicks;
        private boolean reported;

        boolean add(boolean unslowedOnly, int requiredUnslowedTicks) {
            if (reported) {
                return false;
            }
            if (unslowedOnly) {
                unslowedTicks = Math.min(unslowedTicks + 1, requiredUnslowedTicks);
            } else {
                sawSlowed = true;
            }
            reported = sawSlowed && unslowedTicks >= requiredUnslowedTicks;
            return reported;
        }

        void reset() {
            sawSlowed = false;
            unslowedTicks = 0;
            reported = false;
        }
    }
}
