package cc.watchneko.manager.config;

import github.scarsz.configuralize.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
