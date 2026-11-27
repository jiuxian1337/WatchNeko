package cc.watchneko.checks.impl.xray;

import ac.grim.grimac.api.config.ConfigManager;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public record XRaySettings(Blast blast, Path path, SmallVeins smallVeins) {
    private static final List<String> DEFAULT_ORES = List.of("DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "ANCIENT_DEBRIS");

    public record Targets(boolean enabled, Set<String> blocks, Environment environment, double buffer) {
        public Targets {
            blocks = Set.copyOf(blocks);
        }

        public boolean includes(String block) {
            return enabled && blocks.contains(block);
        }
    }

    public record Environment(int radius, int airCount, int caveAirMultiplier, int waterCount, int lavaCount) {}

    public record Blast(Targets targets, long retentionNanos, int matchRadius, double suspiciousRate,
                        double penaltyMultiplier, int minSamples, int minHits) {}

    public record Path(Targets targets, int maxBlocks, long cleanupNanos, int minBlocks, int turningLimit,
                       int branchingLimit, int yShiftLimit, int yShiftMin, FakeCave fakeCave) {}

    public record FakeCave(boolean enabled, int minBlocks, int radius, int sampleLimit,
                           double airRatio, int maxTriggers, long resetNanos) {}

    public record SmallVeins(Targets targets, int distance, int smallSize, int triggerCount, int maxScanBlocks) {}

    public boolean enabled() {
        return blast.targets.enabled || path.targets.enabled || smallVeins.targets.enabled;
    }

    public boolean tracksOre(String block) {
        return blast.targets.includes(block) || path.targets.includes(block) || smallVeins.targets.includes(block);
    }

    public long historyNanos() {
        return Math.max(blast.retentionNanos, Math.max(path.cleanupNanos, path.fakeCave.resetNanos));
    }

    public static XRaySettings load(ConfigManager config) {
        Set<String> inherited = blocks(config.getStringListElse("XRay.blocks", DEFAULT_ORES));
        Targets blastTargets = targets(config, "XRayA", inherited, 2);
        Targets pathTargets = targets(config, "XRayB", inherited, 2);
        Targets smallVeinTargets = targets(config, "XRayC", inherited, 1);
        int minSamples = integer(config, "XRayA.min-samples", 8, 1, 512);
        Blast blast = new Blast(blastTargets, TimeUnit.SECONDS.toNanos(integer(config, "XRayA.retention-seconds", 600, 1, 3600)),
                integer(config, "XRayA.match-radius", 1, 0, 5), decimal(config, "XRayA.suspicious-rate", 0.1, 0, 1),
                decimal(config, "XRayA.penalty-multiplier", 2, 0.01, 100),
                minSamples, integer(config, "XRayA.min-hits", 2, 1, 512));
        FakeCave fakeCave = new FakeCave(config.getBooleanElse("XRayB.fake-cave-detection.enabled", true),
                integer(config, "XRayB.fake-cave-detection.min-blocks", 10, 1, 4096),
                integer(config, "XRayB.fake-cave-detection.radius", 1, 0, 3),
                integer(config, "XRayB.fake-cave-detection.sample-limit", 20, 1, 128),
                decimal(config, "XRayB.fake-cave-detection.air-ratio", 0.3, 0, 1),
                integer(config, "XRayB.fake-cave-detection.max-triggers", 5, 0, 100),
                minutes(config, "XRayB.fake-cave-detection.reset-minutes", 20));
        Path path = new Path(pathTargets, integer(config, "XRayB.path-max-blocks", 500, 1, 4096),
                minutes(config, "XRayB.cleanup-after-minutes", 15), integer(config, "XRayB.min-path-blocks", 10, 1, 4096),
                integer(config, "XRayB.turning-limit", 10, 0, 4096), integer(config, "XRayB.branching-limit", 6, 0, 4096),
                integer(config, "XRayB.y-shift-limit", 4, 0, 4096), integer(config, "XRayB.y-shift-min", 3, 1, 4096), fakeCave);
        SmallVeins smallVeins = new SmallVeins(smallVeinTargets, integer(config, "XRayC.distance", 5, 1, 16),
                integer(config, "XRayC.small-size", 4, 1, 128), integer(config, "XRayC.trigger-count", 3, 1, 128),
                integer(config, "XRayC.max-scan-blocks", 96, 1, 2048));
        return new XRaySettings(blast, path, smallVeins);
    }

    private static Targets targets(ConfigManager config, String key, Set<String> inherited, double buffer) {
        List<String> configured = config.getStringListElse(key + ".blocks", List.of());
        Set<String> selected = configured.isEmpty() ? inherited : blocks(configured);
        String environment = key + ".environment.";
        return new Targets(config.getBooleanElse(key + ".enabled", true) && !selected.isEmpty(), selected,
                new Environment(integer(config, environment + "radius", 3, 0, 5),
                        integer(config, environment + "air-count", 14, 1, 14641),
                        integer(config, environment + "cave-air-multiplier", 5, 1, 32),
                        integer(config, environment + "water-count", 14, 1, 1331),
                        integer(config, environment + "lava-count", 14, 1, 1331)),
                decimal(config, key + ".buffer", buffer, 0.01, 1000));
    }

    private static Set<String> blocks(List<String> configured) {
        Set<String> result = new HashSet<>();
        for (String value : configured) {
            String material = value.trim().toLowerCase(Locale.ROOT);
            if (material.startsWith("minecraft:")) material = material.substring("minecraft:".length());
            if (!material.isEmpty()) result.add(materialName(material));
        }
        return Set.copyOf(result);
    }

    public static String materialName(String name) {
        String material = name.toLowerCase(Locale.ROOT);
        return material.equals("glowing_redstone_ore") ? "redstone_ore" : material;
    }

    public static String oreFamily(String block) {
        return block.startsWith("deepslate_") && block.endsWith("_ore") ? block.substring("deepslate_".length()) : block;
    }

    public boolean tracksFamily(String family) {
        return tracksFamily(blast.targets, family) || tracksFamily(path.targets, family) || tracksFamily(smallVeins.targets, family);
    }

    private static boolean tracksFamily(Targets targets, String family) {
        return targets.includes(family) || targets.includes("deepslate_" + family);
    }

    private static long minutes(ConfigManager config, String key, int fallback) {
        return TimeUnit.MINUTES.toNanos(integer(config, key, fallback, 1, 120));
    }

    private static int integer(ConfigManager config, String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, config.getIntElse(key, fallback)));
    }

    private static double decimal(ConfigManager config, String key, double fallback, double min, double max) {
        double value = config.getDoubleElse(key, fallback);
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
