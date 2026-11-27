package cc.watchneko.manager.config;

import github.scarsz.configuralize.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigCompleterTest {
    @TempDir
    Path folder;

    @Test
    void migratesAutoclickerSettingsBeforeAddingDefaults() throws Exception {
        Path config = folder.resolve("config.yml");
        String original = """
                'AutoclickerF': # existing comment
                    enabled: true
                    window-size: 32
                """;
        Files.writeString(config, original);

        var result = ConfigCompleter.completeAll(getClass(), Language.EN, folder.toFile()).get(0);
        assertFalse(result.failed());
        String migrated = Files.readString(config);
        Map<String, Object> sections = new Yaml().load(migrated);
        Map<?, ?> settings = (Map<?, ?>) sections.get("AutoclickerB");
        assertEquals(true, settings.get("enabled"));
        assertEquals(32, settings.get("window-size"));
        assertEquals(2, settings.get("required-matches"));
        assertFalse(sections.containsKey("AutoclickerF"));
        assertTrue(migrated.contains("'AutoclickerB': # existing comment"));
        assertEquals(original, Files.readString(folder.resolve("config.yml.bak")));
        assertTrue(ConfigCompleter.completeAll(getClass(), Language.EN, folder.toFile()).isEmpty());
    }

    @Test
    void keepsTheCurrentAutoclickerSectionWhenBothNamesExist() throws Exception {
        Path config = folder.resolve("config.yml");
        Files.writeString(config, """
                AutoclickerF:
                    enabled: true
                AutoclickerB:
                    enabled: false
                """);

        var result = ConfigCompleter.completeAll(getClass(), Language.EN, folder.toFile()).get(0);
        assertFalse(result.failed());
        Map<String, Object> sections = new Yaml().load(Files.readString(config));
        assertEquals(true, ((Map<?, ?>) sections.get("AutoclickerF")).get("enabled"));
        assertEquals(false, ((Map<?, ?>) sections.get("AutoclickerB")).get("enabled"));
    }

    @Test
    void migratesTheSharedXRayListBeforeRenamingTheExplosionCheck() throws Exception {
        Path config = folder.resolve("config.yml");
        String original = """
                XRayA:
                    blocks:
                        - EMERALD_ORE
                "XRayD": # blast overrides
                    enabled: false
                    blocks: [ANCIENT_DEBRIS]
                    retention-seconds: 180
                XRayE:
                    turning-limit: 14
                XRayF:
                    small-size: 6
                """;
        Files.writeString(config, original);

        var result = ConfigCompleter.completeAll(getClass(), Language.ZH, folder.toFile()).get(0);
        assertFalse(result.failed());
        String migrated = Files.readString(config);
        Map<String, Object> sections = new Yaml().load(migrated);
        assertEquals(List.of("EMERALD_ORE"), ((Map<?, ?>) sections.get("XRay")).get("blocks"));
        Map<?, ?> blast = (Map<?, ?>) sections.get("XRayA");
        assertEquals(false, blast.get("enabled"));
        assertEquals(List.of("ANCIENT_DEBRIS"), blast.get("blocks"));
        assertEquals(180, blast.get("retention-seconds"));
        Map<?, ?> path = (Map<?, ?>) sections.get("XRayB");
        assertEquals(true, path.get("enabled"));
        assertEquals(14, path.get("turning-limit"));
        Map<?, ?> smallVeins = (Map<?, ?>) sections.get("XRayC");
        assertEquals(true, smallVeins.get("enabled"));
        assertEquals(6, smallVeins.get("small-size"));
        assertFalse(sections.containsKey("XRayD"));
        assertFalse(sections.containsKey("XRayE"));
        assertFalse(sections.containsKey("XRayF"));
        assertTrue(migrated.contains("\"XRayA\": # blast overrides"));
        assertEquals(original, Files.readString(folder.resolve("config.yml.bak")));
        assertTrue(ConfigCompleter.completeAll(getClass(), Language.ZH, folder.toFile()).isEmpty());
    }

    @Test
    void migratesTheSharedXRayListFromAFlowMapping() throws Exception {
        Path config = folder.resolve("config.yml");
        String original = """
                'XRayA': { blocks: [EMERALD_ORE] } # shared ore list
                XRayD: { enabled: false, retention-seconds: 180 }
                XRayE:
                    turning-limit: 14
                """;
        Files.writeString(config, original);

        var result = ConfigCompleter.completeAll(getClass(), Language.EN, folder.toFile()).get(0);
        assertFalse(result.failed());
        String migrated = Files.readString(config);
        Map<String, Object> sections = new Yaml().load(migrated);
        assertEquals(List.of("EMERALD_ORE"), ((Map<?, ?>) sections.get("XRay")).get("blocks"));
        Map<?, ?> blast = (Map<?, ?>) sections.get("XRayA");
        assertEquals(false, blast.get("enabled"));
        assertEquals(180, blast.get("retention-seconds"));
        assertEquals(14, ((Map<?, ?>) sections.get("XRayB")).get("turning-limit"));
        assertFalse(sections.containsKey("XRayD"));
        assertTrue(migrated.contains("'XRay': { blocks: [EMERALD_ORE] } # shared ore list"));
        assertEquals(original, Files.readString(folder.resolve("config.yml.bak")));
        assertTrue(ConfigCompleter.completeAll(getClass(), Language.EN, folder.toFile()).isEmpty());
    }

    @Test
    void currentXRaySectionsTakePrecedenceOverLegacySections() throws Exception {
        Path config = folder.resolve("config.yml");
        Files.writeString(config, """
                XRay:
                    blocks: [EMERALD_ORE]
                XRayA:
                    blocks: [DIAMOND_ORE]
                XRayD:
                    enabled: false
                    retention-seconds: 120
                """);

        var result = ConfigCompleter.completeAll(getClass(), Language.EN, folder.toFile()).get(0);
        assertFalse(result.failed());
        Map<String, Object> sections = new Yaml().load(Files.readString(config));
        assertEquals(List.of("EMERALD_ORE"), ((Map<?, ?>) sections.get("XRay")).get("blocks"));
        Map<?, ?> blast = (Map<?, ?>) sections.get("XRayA");
        assertEquals(List.of("DIAMOND_ORE"), blast.get("blocks"));
        assertEquals(true, blast.get("enabled"));
        assertEquals(600, blast.get("retention-seconds"));
        assertEquals(false, ((Map<?, ?>) sections.get("XRayD")).get("enabled"));
    }
}
