package cc.watchneko.checks.impl.crash;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;

import java.util.ArrayDeque;
import java.util.List;

final class CrashItemText {
    private static final int MAX_NODES = 1024;
    private static final int MAX_DYNAMIC_NODES = 64;

    private CrashItemText() {}

    /** Measures text without recursively serializing components supplied by the client. */
    static int size(Component root, int limit) {
        if (root == null) return 0;
        ArrayDeque<Component> pending = new ArrayDeque<>();
        pending.add(root);
        int scheduled = 1;
        int dynamic = 0;
        int bytes = 0;
        while (!pending.isEmpty()) {
            Component component = pending.removeFirst();
            List<Component> arguments = List.of();
            if (component instanceof TextComponent text) {
                bytes += ExperimentalCrashCheck.utf8Bytes(text.content());
            } else if (component instanceof TranslatableComponent translation) {
                arguments = translation.args();
                bytes += ExperimentalCrashCheck.utf8Bytes(translation.key());
                dynamic++;
            } else if (component instanceof KeybindComponent keybind) {
                bytes += ExperimentalCrashCheck.utf8Bytes(keybind.keybind());
                dynamic++;
            }
            List<Component> children = component.children();
            scheduled += children.size() + arguments.size();
            if (bytes > limit || scheduled > MAX_NODES || dynamic > MAX_DYNAMIC_NODES) return limit + 1;
            pending.addAll(children);
            pending.addAll(arguments);
            if (component.hoverEvent() != null && component.hoverEvent().value() instanceof Component hoverText) {
                if (++scheduled > MAX_NODES) return limit + 1;
                pending.addLast(hoverText);
            }
        }
        return bytes;
    }
}
