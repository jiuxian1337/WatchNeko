package cc.watchneko.manager.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the completer against the real bundled files rather than hand-written samples.
 *
 * <p>The merge logic is only interesting if it survives the files this project actually ships, so these
 * tests take the shipped default, delete a few keys from it to simulate an older server, and check the
 * result parses back to the same set of keys with the operator's own values intact.
 */
class ConfigCompleterShippedFilesTest {

    @TempDir
    Path directory;

    private static List<String> resource(final String name) {
        try (InputStream stream = ConfigCompleterShippedFilesTest.class.getResourceAsStream(name)) {
            assertNotNull(stream, "bundled resource " + name + " must exist");
            // Split on \r?\n and use Arrays.asList: List.of wraps the array, and its indexOf compares by
            // reference. The shipped files are CRLF on a Windows checkout, so the \r has to go too.
            return java.util.Arrays.asList(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n", -1));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static List<String> dropKeys(final List<String> lines, final String... prefixes) {
        final List<String> result = new java.util.ArrayList<>();
        for (final String line : lines) {
            final String trimmed = line.strip();
            boolean drop = false;
            for (final String prefix : prefixes) {
                if (trimmed.startsWith(prefix + ":") || trimmed.startsWith("- ") && !result.isEmpty()) {
                    // Only drop sequence items belonging to a dropped key; the flag below handles that.
                    continue;
                }
                if (trimmed.startsWith(prefix + ":")) {
                    drop = true;
                }
            }
            if (!drop) {
                result.add(line);
            }
        }
        return result;
    }

    @Test
    void completesTheShippedConfigFile() {
        final List<String> shipped = resource("/config/en.yml");

        // Simulate a config written by an older release: it simply stops before the keys that did not
        // exist yet. Deleting individual keys is not realistic — their comment lines would remain and be
        // re-attached to the following key.
        final int cut = shipped.indexOf("# Per-player prediction toggles. These are the config-level defaults; a plugin API caller can override");
        assertTrue(cut > 0, "the shipped config must contain the block this test truncates at");
        final List<String> older = new java.util.ArrayList<>(shipped.subList(0, cut));

        final ConfigCompleter.Merge merge = ConfigCompleter.merge(older, shipped);

        assertFalse(merge.added().isEmpty());
        for (final String key : List.of(
                "exempt-elytra", "force-stuck-speed", "force-slow-movement", "transaction-skip-limit")) {
            assertTrue(merge.added().contains(key), key + " missing from " + merge.added());
        }

        // Everything the old file had is still there, unchanged and in order.
        assertEquals(older, merge.lines().subList(0, older.size()));
    }

    @Test
    void completingTheShippedFilesIsIdempotent() {
        for (final String name : List.of("/config/en.yml", "/messages/en.yml", "/discord/en.yml")) {
            final List<String> shipped = resource(name);
            final List<String> older = shipped.subList(0, Math.max(1, shipped.size() / 2));

            final ConfigCompleter.Merge first = ConfigCompleter.merge(older, shipped);
            final ConfigCompleter.Merge second = ConfigCompleter.merge(first.lines(), shipped);

            assertTrue(second.added().isEmpty(), name + " second pass added " + second.added());
            assertEquals(first.lines(), second.lines(), name + " second pass changed the file");
        }
    }

    @Test
    void completeAllWritesABackupAndLeavesTheOperatorsValuesAlone() throws IOException {
        final Path config = directory.resolve("config.yml");
        Files.writeString(config, "max-ping: 999\n# my own note\nAnalysisA:\n    batch-size: 4\n");

        ConfigCompleter.completeAll(
                ConfigCompleterShippedFilesTest.class,
                github.scarsz.configuralize.Language.EN,
                directory.toFile());

        final String written = Files.readString(config);
        assertTrue(written.contains("max-ping: 999"), "operator value must survive");
        assertTrue(written.contains("# my own note"), "operator comment must survive");
        assertTrue(written.contains("batch-size: 4"), "operator value must survive");
        assertTrue(written.contains("transaction-skip-limit:"), "missing key must be added");

        assertTrue(Files.exists(directory.resolve("config.yml.bak")), "a backup must be written");
        assertEquals("max-ping: 999\n# my own note\nAnalysisA:\n    batch-size: 4\n",
                Files.readString(directory.resolve("config.yml.bak")));

        // A second run must not touch the file or the backup.
        Files.writeString(directory.resolve("config.yml.bak"), "sentinel");
        ConfigCompleter.completeAll(
                ConfigCompleterShippedFilesTest.class,
                github.scarsz.configuralize.Language.EN,
                directory.toFile());
        assertEquals("sentinel", Files.readString(directory.resolve("config.yml.bak")));
    }

    @Test
    void leavesAFullyCompleteFileUntouched() throws IOException {
        final Path config = directory.resolve("config.yml");
        final String original = String.join("\n", resource("/config/en.yml"));
        Files.writeString(config, original);

        ConfigCompleter.completeAll(
                ConfigCompleterShippedFilesTest.class,
                github.scarsz.configuralize.Language.EN,
                directory.toFile());

        assertEquals(original, Files.readString(config));
        assertFalse(Files.exists(directory.resolve("config.yml.bak")), "no backup when nothing is added");
    }

    @Test
    void skipsFilesThatDoNotExist() {
        ConfigCompleter.completeAll(
                ConfigCompleterShippedFilesTest.class,
                github.scarsz.configuralize.Language.EN,
                directory.toFile());

        assertFalse(Files.exists(directory.resolve("config.yml")));
    }

    @Test
    void keepsCrlfLineEndings() throws IOException {
        // Windows servers have CRLF configs; rewriting them as LF would show up as a whole-file diff.
        final Path config = directory.resolve("config.yml");
        Files.writeString(config, "max-ping: 999\r\nAnalysisA:\r\n    batch-size: 4\r\n");

        ConfigCompleter.completeAll(
                ConfigCompleterShippedFilesTest.class,
                github.scarsz.configuralize.Language.EN,
                directory.toFile());

        final String written = Files.readString(config);
        assertTrue(written.contains("transaction-skip-limit:"), "missing key must be added");
        assertFalse(written.replace("\r\n", "").contains("\n"), "every line break must stay CRLF");
    }

    // -- the version marker -------------------------------------------------------------------------

    @Test
    void a_missing_version_marker_is_written() throws IOException {
        final Path config = directory.resolve("config.yml");
        Files.writeString(config, "max-ping: 999\n");

        ConfigCompleter.completeAll(
                ConfigCompleterShippedFilesTest.class,
                github.scarsz.configuralize.Language.EN,
                directory.toFile());

        final String written = Files.readString(config);
        assertTrue(written.contains("config-version: "), "the marker must be written");
        assertTrue(written.stripTrailing().endsWith("config-version: " + shippedVersion()),
                "the marker must be the last line and carry the shipped version");
    }

    @Test
    void a_stale_version_marker_is_rewritten_in_place() throws IOException {
        final Path config = directory.resolve("config.yml");
        Files.writeString(config, "max-ping: 999\nconfig-version: 3\nother: 1\n");

        ConfigCompleter.completeAll(
                ConfigCompleterShippedFilesTest.class,
                github.scarsz.configuralize.Language.EN,
                directory.toFile());

        final String written = Files.readString(config);
        assertTrue(written.contains("config-version: " + shippedVersion()));
        assertFalse(written.contains("config-version: 3"), "the old number must be gone");
        assertTrue(written.indexOf("other: 1") > written.indexOf("config-version:"),
                "the marker keeps its position rather than moving to the end");
        assertTrue(written.contains("max-ping: 999"), "operator values survive");
    }

    @Test
    void the_marker_is_forced_even_when_the_file_claims_a_newer_version() throws IOException {
        // Completion does not depend on the marker: a file claiming a future version still gets its keys.
        final Path config = directory.resolve("config.yml");
        Files.writeString(config, "max-ping: 999\nconfig-version: 999\n");

        ConfigCompleter.completeAll(
                ConfigCompleterShippedFilesTest.class,
                github.scarsz.configuralize.Language.EN,
                directory.toFile());

        final String written = Files.readString(config);
        assertTrue(written.contains("transaction-skip-limit:"), "keys are completed regardless of version");
        assertTrue(written.contains("config-version: " + shippedVersion()), "and the marker is corrected");
        assertFalse(written.contains("config-version: 999"));
    }

    @Test
    void a_complete_file_still_gets_its_marker_stamped_once() throws IOException {
        final Path config = directory.resolve("config.yml");
        final String shipped = String.join("\n", resource("/config/en.yml")).replace(
                "config-version: " + shippedVersion(), "config-version: 1");
        Files.writeString(config, shipped);

        ConfigCompleter.completeAll(
                ConfigCompleterShippedFilesTest.class,
                github.scarsz.configuralize.Language.EN,
                directory.toFile());
        final String once = Files.readString(config);
        assertTrue(once.contains("config-version: " + shippedVersion()));

        // Second run is a no-op: nothing left to add, marker already correct.
        ConfigCompleter.completeAll(
                ConfigCompleterShippedFilesTest.class,
                github.scarsz.configuralize.Language.EN,
                directory.toFile());
        assertEquals(once, Files.readString(config));
    }

    private static int shippedVersion() {
        for (final String line : resource("/config/en.yml")) {
            final String trimmed = line.strip();
            if (trimmed.startsWith("config-version:")) {
                return Integer.parseInt(trimmed.substring("config-version:".length()).trim());
            }
        }
        throw new AssertionError("the shipped config must carry a config-version marker");
    }
}
