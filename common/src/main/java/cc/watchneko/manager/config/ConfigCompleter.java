package cc.watchneko.manager.config;

import github.scarsz.configuralize.Language;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds keys that the bundled default file has but the user's copy does not.
 *
 * <p>Configuralize only writes a default file when none exists: {@code saveAllDefaults(false)} returns
 * immediately for an existing file, so every key added in a later release is invisible to servers that
 * already have a {@code config.yml}. This class closes that gap by inserting the missing blocks —
 * comments included — from the bundled resource into the user's file.
 *
 * <p>It only ever <em>inserts</em>. Existing lines are never rewritten, reordered or removed, so a value
 * the operator changed stays exactly as it is, and anything this parser fails to understand is left
 * alone rather than dropped. Running it twice adds nothing the second time.
 *
 * <p>The parser is deliberately small: it understands the shape these four files actually use (nested
 * mappings, scalar leaves, inline comments and block sequences). It does not try to be a YAML
 * implementation, and it does not need to be — the input is a file this project ships.
 */
final class ConfigCompleter {

    /** The files that get completed. {@code punishments.yml} is absent on purpose: it is user-defined data. */
    private static final String[] SOURCES = {"config", "messages", "discord"};

    /** The file that carries the version marker. Only {@code config.yml} has one. */
    private static final String VERSIONED_SOURCE = "config";

    /** The marker key. Written on every run; never used to decide what to complete. */
    private static final String VERSION_KEY = "config-version";

    /** A mapping key: leading spaces, the key, then anything (an inline value and/or a trailing comment). */
    private static final Pattern KEY = Pattern.compile("^( *)([A-Za-z0-9_.\\-]+):(.*)$");

    /**
     * A top-level version marker, matched anywhere so a stale one can be rewritten in place.
     *
     * <p>A trailing comment is tolerated: an operator who annotates the line has not changed what the
     * marker means, and failing to match would append a second {@code config-version} rather than
     * rewriting the first.
     */
    private static final Pattern VERSION_LINE =
            Pattern.compile("^" + VERSION_KEY + ":\\s*(\\d*)\\s*(?:#.*)?$");

    private ConfigCompleter() {
    }

    /** Completes every supported file in {@code dataFolder} and returns one report per file that changed. */
    static List<Result> completeAll(final Class<?> anchor, final Language language, final File dataFolder) {
        final List<Result> results = new ArrayList<>();
        for (final String source : SOURCES) {
            final File file = new File(dataFolder, source + ".yml");
            if (!file.isFile()) {
                continue; // saveAllDefaults already wrote a complete file
            }
            final List<String> defaults = readResource(anchor, source, language);
            if (defaults == null) {
                continue;
            }
            try {
                completeFile(file, source, defaults).ifPresent(results::add);
            } catch (final IOException exception) {
                results.add(new Result(file, List.of(), exception));
            }
        }
        return results;
    }

    /** What happened to one file: the keys added, or the failure that stopped them. */
    record Result(File file, List<String> added, Exception failure) {
        boolean failed() {
            return failure != null;
        }
    }

    /**
     * Checks that a completed file still parses, before it replaces the operator's own.
     *
     * <p>An interface so the merge logic stays testable without a YAML implementation, and so the real
     * check can be swapped for one that always passes if a future platform cannot carry SnakeYAML.
     */
    interface Validation {
        /** @return a description of the problem, or {@code null} when the text parses */
        String check(String yaml);
    }

    /** The production check: the same SnakeYAML the loader uses, with the same options. */
    static final Validation SNAKEYAML_VALIDATION = new Validation() {
        @Override
        public String check(final String yaml) {
            final LoaderOptions options = new LoaderOptions();
            // A duplicate key is what an interrupted earlier run could leave behind, and it is not what
            // this check is for: it would reject files the loader itself accepts.
            options.setAllowDuplicateKeys(true);
            try {
                new Yaml(new SafeConstructor(options)).load(yaml);
                return null;
            } catch (final RuntimeException exception) {
                return exception.getMessage();
            }
        }
    };

    /** Swappable so a test can drive the failure path without writing invalid YAML by hand. */
    static Validation validation = SNAKEYAML_VALIDATION;

    private static java.util.Optional<Result> completeFile(
            final File file, final String source, final List<String> defaults) throws IOException {
        final String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        final String newline = text.contains("\r\n") ? "\r\n" : "\n";
        final List<String> userLines = splitLines(text);

        final Merge merge = merge(userLines, defaults);
        final List<String> added = new ArrayList<>(merge.added());

        // The marker is forced to the current version every run, whether or not anything else changed.
        // It is a record of which release last touched this file, not an input to the completion: an
        // operator whose file claims a newer version still gets their missing keys. A bundled file with
        // no marker has no version to force, so the line is left exactly as the operator wrote it.
        final int version = source.equals(VERSIONED_SOURCE) ? currentVersion(defaults) : -1;
        final List<String> completed = version < 0 ? merge.lines() : stampVersion(merge.lines(), version, added);

        if (completed.equals(userLines)) {
            return java.util.Optional.empty();
        }

        // Refuse to write a file the loader would then choke on. The merge only ever inserts, but an
        // operator can hand this a file whose indentation makes the inserted block land somewhere it
        // does not belong; parsing the result is a cheap way to find out before the damage is on disk.
        final String rendered = String.join(newline, completed) + newline;
        final String problem = ConfigCompleter.validation.check(rendered);
        if (problem != null) {
            throw new IOException("the completed " + file.getName() + " would not parse (" + problem
                    + "); the file was left untouched");
        }

        // Rewriting an operator's file is worth an undo button, and this only happens when something
        // actually changes, so the backup cannot be overwritten on every start.
        final File backup = new File(file.getParentFile(), source + ".yml.bak");
        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        writeAtomically(file, rendered);

        return java.util.Optional.of(new Result(file, added, null));
    }

    /**
     * Writes through a temporary file in the same directory, then moves it into place.
     *
     * <p>A plain write that is interrupted — full disk, a kill, a power cut — leaves a truncated config
     * behind, and the backup taken a moment earlier would be the only copy. The move is atomic on the
     * file systems these servers actually run on, so the file is either the old one or the new one.
     */
    private static void writeAtomically(final File file, final String contents) throws IOException {
        final File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            Files.writeString(temporary.toPath(), contents, StandardCharsets.UTF_8);
            Files.move(temporary.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException exception) {
            Files.deleteIfExists(temporary.toPath());
            throw exception;
        }
    }

    /**
     * Rewrites the {@code config-version} marker to {@code version}, adding it when absent.
     *
     * <p>A stale marker is replaced in place, so the operator does not end up with two of them and the
     * key keeps its position at the end of the file. A file that already carries the right number is
     * returned untouched, which is what keeps a second run a no-op.
     */
    static List<String> stampVersion(
            final List<String> lines, final int version, final List<String> added) {
        final String marker = VERSION_KEY + ": " + version;
        final List<String> result = new ArrayList<>(lines);

        for (int index = 0; index < result.size(); index++) {
            if (VERSION_LINE.matcher(result.get(index)).matches()) {
                if (!result.get(index).equals(marker)) {
                    result.set(index, marker);
                    if (!added.contains(VERSION_KEY)) {
                        added.add(VERSION_KEY);
                    }
                }
                return result;
            }
        }

        while (!result.isEmpty() && result.get(result.size() - 1).isBlank()) {
            result.remove(result.size() - 1);
        }
        if (!result.isEmpty()) {
            result.add("");
        }
        result.add(marker);
        added.add(VERSION_KEY);
        return result;
    }

    /**
     * The version the bundled file carries, or {@code -1} when it carries no marker.
     *
     * <p>Absence is not zero. A bundled file without a marker means this build has no version to stamp,
     * and writing {@code config-version: 0} onto every server's file would be inventing one.
     */
    static int currentVersion(final List<String> defaults) {
        for (final String line : defaults) {
            final Matcher matcher = VERSION_LINE.matcher(line.strip());
            if (matcher.matches()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (final NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** The completed lines plus the key paths that were inserted, for logging. */
    record Merge(List<String> lines, List<String> added) {
    }

    /**
     * Inserts every key present in {@code defaultLines} but absent from {@code userLines}.
     *
     * <p>Missing top-level keys are appended at the end; missing nested keys are inserted at the end of
     * their parent's block, shifted to whatever indentation the user's file already uses there.
     */
    static Merge merge(final List<String> userLines, final List<String> defaultLines) {
        final List<Node> userTree = parse(userLines);
        final Map<Integer, List<String>> insertions = new TreeMap<>();
        final List<String> added = new ArrayList<>();

        // Top-level additions are keyed one past the last line, so the loop below never sees them twice.
        mergeInto(parse(defaultLines), userTree, userLines.size(), 0, "", insertions, added,
                userLines.isEmpty() || !userLines.get(userLines.size() - 1).isBlank());

        if (added.isEmpty()) {
            return new Merge(userLines, List.of());
        }

        // One pass, keyed by the original line index: inserting into a copy while iterating the original
        // indices would shift every later insertion point by the number of lines already added.
        final List<String> result = new ArrayList<>(userLines.size() + 16);
        for (int index = 0; index < userLines.size(); index++) {
            result.add(userLines.get(index));
            result.addAll(insertions.getOrDefault(index, List.of()));
        }
        result.addAll(insertions.getOrDefault(userLines.size(), List.of()));
        return new Merge(result, added);
    }

    private static void mergeInto(
            final List<Node> defaults,
            final List<Node> users,
            final int insertAfter,
            final int childIndent,
            final String prefix,
            final Map<Integer, List<String>> insertions,
            final List<String> added,
            final boolean separatorNeeded) {
        for (final Node missing : defaults) {
            final String path = prefix.isEmpty() ? missing.key : prefix + "." + missing.key;
            final Node existing = find(users, missing.key);

            if (existing == null) {
                final List<String> block = new ArrayList<>();
                // Separate an appended top-level block from whatever the file ended with, once.
                if (prefix.isEmpty() && separatorNeeded
                        && insertions.getOrDefault(insertAfter, List.of()).isEmpty()) {
                    block.add("");
                }
                render(missing, childIndent - missing.indent, block);
                insertions.computeIfAbsent(insertAfter, key -> new ArrayList<>()).addAll(block);
                added.add(path);
                continue;
            }

            if (existing.inlineValue || missing.children.isEmpty()) {
                // Either it is a scalar (children would be invalid YAML) or there is nothing below it.
                continue;
            }

            // Match the indentation the operator's file already uses for this parent's children.
            final int hint = existing.children.isEmpty() ? existing.indent + 4 : existing.children.get(0).indent;
            mergeInto(missing.children, existing.children, existing.lastLine(), hint, path, insertions, added,
                    separatorNeeded);
        }
    }

    private static Node find(final List<Node> nodes, final String key) {
        for (final Node node : nodes) {
            if (node.key.equals(key)) {
                return node;
            }
        }
        return null;
    }

    /** Writes a node and its children, shifting every line by {@code shift} spaces. */
    private static void render(final Node node, final int shift, final List<String> out) {
        for (final String line : node.header) {
            out.add(shift(line, shift));
        }
        for (final String line : node.listLines) {
            out.add(shift(line, shift));
        }
        for (final Node child : node.children) {
            render(child, shift, out);
        }
    }

    private static String shift(final String line, final int shift) {
        if (shift > 0) {
            return " ".repeat(shift) + line;
        }
        if (shift < 0) {
            int removable = 0;
            while (removable < -shift && removable < line.length() && line.charAt(removable) == ' ') {
                removable++;
            }
            return line.substring(removable);
        }
        return line;
    }

    private static List<Node> parse(final List<String> lines) {
        final List<Node> roots = new ArrayList<>();
        final Deque<Node> stack = new ArrayDeque<>();
        final List<String> comments = new ArrayList<>();

        for (int index = 0; index < lines.size(); index++) {
            final String line = lines.get(index);
            final String trimmed = line.strip();

            if (trimmed.isEmpty()) {
                comments.clear();
                continue;
            }
            if (trimmed.startsWith("#")) {
                comments.add(line);
                continue;
            }
            if (trimmed.startsWith("-")) {
                // A sequence item belongs to the key above it; it is copied with that key, never on its own.
                comments.clear();
                if (!stack.isEmpty()) {
                    stack.peek().listLines.add(line);
                    stack.peek().lastListLine = index;
                }
                continue;
            }

            final Matcher matcher = KEY.matcher(line);
            if (!matcher.matches()) {
                comments.clear();
                continue;
            }

            final int indent = matcher.group(1).length();
            final String rest = matcher.group(3);
            final Node node = new Node(
                    matcher.group(2),
                    indent,
                    List.copyOf(comments),
                    line,
                    index,
                    !rest.strip().isEmpty() && !rest.strip().startsWith("#"));
            comments.clear();

            while (!stack.isEmpty() && stack.peek().indent >= indent) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.peek().children.add(node);
            }
            stack.push(node);
        }
        return roots;
    }

    private static List<String> readResource(final Class<?> anchor, final String source, final Language language) {
        for (final Language attempt : new Language[]{language, Language.EN}) {
            if (attempt == null) {
                continue;
            }
            final String path = "/" + source + "/" + attempt.getCode().toLowerCase(Locale.ROOT) + ".yml";
            try (InputStream stream = anchor.getResourceAsStream(path)) {
                if (stream != null) {
                    return splitLines(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (final IOException exception) {
                // Nothing to complete from a resource we cannot read; the caller logs the missing file.
                return null;
            }
        }
        return null;
    }

    private static List<String> splitLines(final String text) {
        final List<String> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                int end = index;
                if (end > start && text.charAt(end - 1) == '\r') {
                    end--;
                }
                lines.add(text.substring(start, end));
                start = index + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /** One mapping entry: the comment block above it, its key line, its sequence items, and its children. */
    private static final class Node {
        private final String key;
        private final int indent;
        private final List<String> header;
        private final List<String> listLines = new ArrayList<>();
        private final List<Node> children = new ArrayList<>();
        private final int lineIndex;
        private final boolean inlineValue;
        private int lastListLine = -1;

        private Node(
                final String key,
                final int indent,
                final List<String> comments,
                final String keyLine,
                final int lineIndex,
                final boolean inlineValue) {
            this.key = key;
            this.indent = indent;
            this.header = new ArrayList<>(comments);
            this.header.add(keyLine);
            this.lineIndex = lineIndex;
            this.inlineValue = inlineValue;
        }

        /** The last line of this entry's whole block, which is where a missing sibling gets inserted. */
        private int lastLine() {
            int last = Math.max(lineIndex, lastListLine);
            for (final Node child : children) {
                last = Math.max(last, child.lastLine());
            }
            return last;
        }
    }
}
