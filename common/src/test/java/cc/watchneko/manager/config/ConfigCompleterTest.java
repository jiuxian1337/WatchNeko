package cc.watchneko.manager.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two properties that make it safe to rewrite a server operator's config on startup: nothing
 * that was already there is touched, and running it twice is a no-op.
 */
class ConfigCompleterTest {

    private static List<String> lines(final String... lines) {
        return List.of(lines);
    }

    private static String text(final ConfigCompleter.Merge merge) {
        return String.join("\n", merge.lines());
    }

    @Test
    void appendsTopLevelKeysTheFileIsMissing() {
        final ConfigCompleter.Merge merge = ConfigCompleter.merge(
                lines("max-ping: 120"),
                lines("max-ping: 999", "# a new setting", "transaction-skip-limit: 50"));

        assertEquals(List.of("transaction-skip-limit"), merge.added());
        assertEquals("max-ping: 120\n\n# a new setting\ntransaction-skip-limit: 50", text(merge));
    }

    @Test
    void keepsTheOperatorsValueWhenTheKeyExists() {
        final ConfigCompleter.Merge merge = ConfigCompleter.merge(
                lines("max-ping: 120"),
                lines("max-ping: 999"));

        assertTrue(merge.added().isEmpty());
        assertEquals("max-ping: 120", text(merge));
    }

    @Test
    void insertsNestedKeysAtTheEndOfTheirParentBlock() {
        final ConfigCompleter.Merge merge = ConfigCompleter.merge(
                lines("AnalysisA:", "    batch-size: 8", "other: 1"),
                lines("AnalysisA:", "    batch-size: 8", "    min-cheat-votes: 8", "other: 1"));

        assertEquals(List.of("AnalysisA.min-cheat-votes"), merge.added());
        assertEquals("AnalysisA:\n    batch-size: 8\n    min-cheat-votes: 8\nother: 1", text(merge));
    }

    @Test
    void copiesCommentsAndSequenceItemsWithANewBlock() {
        final ConfigCompleter.Merge merge = ConfigCompleter.merge(
                lines("enabled: false"),
                lines(
                        "# Who to notify",
                        "notify:",
                        "    # the list",
                        "    targets:",
                        "        - \"a\"",
                        "        - \"b\""));

        assertEquals(List.of("notify"), merge.added());
        assertEquals(
                "enabled: false\n"
                        + "\n"
                        + "# Who to notify\n"
                        + "notify:\n"
                        + "    # the list\n"
                        + "    targets:\n"
                        + "        - \"a\"\n"
                        + "        - \"b\"",
                text(merge));
    }

    @Test
    void matchesTheIndentationTheOperatorsFileUses() {
        // Two-space file, four-space defaults: the inserted child must follow the file, not the defaults.
        final ConfigCompleter.Merge merge = ConfigCompleter.merge(
                lines("AnalysisA:", "  batch-size: 8"),
                lines("AnalysisA:", "    batch-size: 8", "    min-cheat-votes: 8"));

        assertEquals(List.of("AnalysisA.min-cheat-votes"), merge.added());
        assertEquals("AnalysisA:\n  batch-size: 8\n  min-cheat-votes: 8", text(merge));
    }

    @Test
    void doesNotTurnAScalarIntoAMapping() {
        // The user replaced a section with a scalar; adding children would produce invalid YAML.
        final ConfigCompleter.Merge merge = ConfigCompleter.merge(
                lines("AnalysisA: false"),
                lines("AnalysisA:", "    batch-size: 8"));

        assertTrue(merge.added().isEmpty());
        assertEquals("AnalysisA: false", text(merge));
    }

    @Test
    void ignoresKeysThatOnlyExistInsideAComment() {
        final ConfigCompleter.Merge merge = ConfigCompleter.merge(
                lines("# transaction-skip-limit: 50 is the default"),
                lines("transaction-skip-limit: 50"));

        assertEquals(List.of("transaction-skip-limit"), merge.added());
    }

    @Test
    void secondRunAddsNothing() {
        final List<String> defaults = lines(
                "max-ping: 120",
                "# A setting",
                "AnalysisA:",
                "    batch-size: 8",
                "    min-cheat-votes: 8",
                "notify:",
                "    - \"a\"");

        final ConfigCompleter.Merge first = ConfigCompleter.merge(lines("max-ping: 60"), defaults);
        assertFalse(first.added().isEmpty());

        final ConfigCompleter.Merge second = ConfigCompleter.merge(first.lines(), defaults);
        assertTrue(second.added().isEmpty(), "second pass should be a no-op");
        assertEquals(first.lines(), second.lines());
    }

    @Test
    void leavesUnknownSyntaxAlone() {
        final List<String> user = lines("keep: |", "    block text", "tail: 1");
        final ConfigCompleter.Merge merge = ConfigCompleter.merge(user, lines("keep: |", "    block text", "tail: 1"));

        assertTrue(merge.added().isEmpty());
        assertEquals(user, merge.lines());
    }

    @Test
    void doesNotDuplicateABlankLineAtTheEndOfTheFile() {
        final ConfigCompleter.Merge merge = ConfigCompleter.merge(
                lines("max-ping: 120", ""),
                lines("max-ping: 120", "extra: 1"));

        assertEquals("max-ping: 120\n\nextra: 1", text(merge));
    }
}
