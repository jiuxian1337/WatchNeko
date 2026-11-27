package cc.watchneko.manager;

import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.config.ConfigReloadable;
import ac.grim.grimac.api.event.events.CommandExecuteEvent;
import cc.watchneko.WatchNekoAPI;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.events.packets.ProxyAlertMessenger;
import cc.watchneko.platform.api.player.PlatformPlayer;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.LogUtil;
import cc.watchneko.utils.anticheat.MessageUtil;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class PunishmentManager implements ConfigReloadable {
    PlayerData player;
    List<PunishGroup> groups = new ArrayList<>();
    String experimentalSymbol = "*";
    private String alertString;
    private boolean testMode;
    private String proxyAlertString = "";
    private boolean hoverEnabled;
    private String hoverFormat;
    private boolean clickEnabled;
    private String clickCommand;

    public PunishmentManager(PlayerData player) {
        this.player = player;
    }

    @Override
    public void reload(ConfigManager config) {
        List<String> punish = config.getStringListElse("Punishments", new ArrayList<>());
        experimentalSymbol = config.getStringElse("experimental-symbol", "*");
        alertString = config.getStringElse("alerts-format", "%prefix% &f%player% &bfailed &f%check_name%%experimental% &f(x&c%vl%&f)");
        testMode = config.getBooleanElse("test-mode", false);
        proxyAlertString = config.getStringElse("alerts-format-proxy", "%prefix% &f[&cproxy&f] &f%player% &bfailed &f%check_name%%experimental% &f(x&c%vl%&f)");
        hoverEnabled = config.getBooleanElse("alerts.hover.enabled", true);
        hoverFormat = config.getStringElse("alerts-hover-format", "&7%verbose%");
        clickEnabled = config.getBooleanElse("alerts.click.enabled", true);
        clickCommand = config.getStringElse("alerts.click.command", "/watchneko spectate %player%");
        try {
            groups.clear();
            Collection<AbstractCheck> allChecks = player.checkManager.allChecks.values();

            // To support reloading
            for (AbstractCheck check : allChecks) {
                check.setEnabled(false);
            }

            for (Object s : punish) {
                LinkedHashMap<String, Object> map = (LinkedHashMap<String, Object>) s;

                List<String> checks = (List<String>) map.getOrDefault("checks", new ArrayList<>());
                List<String> commands = (List<String>) map.getOrDefault("commands", new ArrayList<>());
                int removeViolationsAfter = (int) map.getOrDefault("remove-violations-after", 300);

                List<ParsedCommand> parsed = new ArrayList<>();
                List<AbstractCheck> checksList = selectChecks(allChecks, checks);

                for (String command : commands) {
                    String firstNum = command.substring(0, command.indexOf(":"));
                    String secondNum = command.substring(command.indexOf(":"), command.indexOf(" "));

                    int threshold = Integer.parseInt(firstNum);
                    int interval = Integer.parseInt(secondNum.substring(1));
                    String commandString = command.substring(command.indexOf(" ") + 1);

                    parsed.add(new ParsedCommand(threshold, interval, commandString));
                }

                groups.add(new PunishGroup(checksList, parsed, removeViolationsAfter * 1000));
                checksList.forEach(check -> check.setEnabled(true));
            }
        } catch (Exception e) {
            LogUtil.error("Error while loading punishments.yml! This is likely your fault!", e);
        }
    }

    static List<AbstractCheck> selectChecks(Collection<AbstractCheck> available, List<String> selectors) {
        List<AbstractCheck> selected = new ArrayList<>();
        Set<AbstractCheck> excluded = new HashSet<>();
        for (String selector : selectors) {
            boolean exclude = selector.startsWith("!");
            String pattern = (exclude ? selector.substring(1) : selector).toLowerCase(Locale.ROOT);
            for (AbstractCheck check : available) {
                if (!matchesCheck(check, pattern, exclude)) continue;
                if (exclude) excluded.add(check);
                else selected.add(check);
            }
        }
        selected.removeAll(excluded);
        return selected;
    }

    private static boolean matchesCheck(AbstractCheck check, String pattern, boolean exclude) {
        String name = check.getCheckName();
        if (name == null) return false;
        String alias = check.getAlternativeName();
        CheckData data = check.getClass().getAnnotation(CheckData.class);
        if (!exclude && data != null && data.requiresExactPunishmentMatch()) {
            return name.equalsIgnoreCase(pattern)
                    || (alias != null && !alias.equals("UNKNOWN") && alias.equalsIgnoreCase(pattern));
        }
        return name.toLowerCase(Locale.ROOT).contains(pattern)
                || (alias != null && alias.toLowerCase(Locale.ROOT).contains(pattern));
    }

    private String replaceAlertPlaceholders(String original, int vl, Check check, String verboseReplacement) {
        return MessageUtil.replacePlaceholders(player, original
                .replace("[alert]", alertString)
                .replace("[proxy]", proxyAlertString)
                .replace("%client%", "%brand%")
                .replace("%check_name%", check.getDisplayName())
                .replace("%experimental%", check.isExperimental() ? experimentalSymbol : "")
                .replace("%vl%", Integer.toString(vl))
                .replace("%description%", check.getDescription())
        ).replace("%verbose%", verboseReplacement);
    }

    private Component buildInteractiveAlert(int vl, Check check, String verbose, boolean proxy) {
        String format = proxy ? proxyAlertString : alertString;

        String mainText = replaceAlertPlaceholders(format, vl, check, "");
        Component component = MessageUtil.miniMessage(mainText);

        if (hoverEnabled) {
            String escapedVerbose = MiniMessage.miniMessage().escapeTags(verbose);
            String hoverText = replaceAlertPlaceholders(hoverFormat, vl, check, escapedVerbose);
            Component hoverComponent = MessageUtil.miniMessage(hoverText);
            component = component.hoverEvent(HoverEvent.showText(hoverComponent));
        }

        if (clickEnabled && clickCommand != null && !clickCommand.isBlank()) {
            String resolvedClickCommand = MessageUtil.replacePlaceholders(player, clickCommand.replace("%client%", "%brand%"));
            component = component.clickEvent(ClickEvent.runCommand(resolvedClickCommand));
        }

        return component;
    }

    public boolean handleAlert(PlayerData player, String verbose, Check check) {
        boolean sentDebug = false;

        // Check commands
        for (PunishGroup group : groups) {
            if (group.checks.contains(check)) {
                final int vl = getViolations(group, check);
                final int violationCount = group.violations.size();

                @Nullable Set<@Nullable PlatformPlayer> verboseListeners = null;

                // Verbose that prints all flags
                if (WatchNekoAPI.INSTANCE.getAlertManager().hasVerboseListeners()) {
                    sentDebug = true;
                    verboseListeners = WatchNekoAPI.INSTANCE.getAlertManager().sendVerbose(buildInteractiveAlert(vl, check, verbose, false), null);
                }

                for (ParsedCommand command : group.commands) {
                    boolean isAlert = command.command.equals("[alert]");
                    boolean isProxy = command.command.equals("[proxy]");
                    Component interactive = null;
                    String cmd;

                    if (isAlert || isProxy) {
                        interactive = buildInteractiveAlert(violationCount, check, verbose, isProxy);
                        cmd = MiniMessage.miniMessage().serialize(interactive);
                    } else {
                        String escapedVerbose = MiniMessage.miniMessage().escapeTags(verbose);
                        cmd = replaceAlertPlaceholders(command.command, violationCount, check, escapedVerbose);
                    }

                    if (violationCount >= command.threshold) {
                        // 0 means execute once
                        // Any other number means execute every X interval
                        boolean inInterval = command.interval == 0 ? (command.executeCount == 0) : (violationCount % command.interval == 0);
                        if (inInterval) {
                            CommandExecuteEvent executeEvent = new CommandExecuteEvent(player, check, verbose, cmd);
                            WatchNekoAPI.INSTANCE.getEventBus().post(executeEvent);
                            if (executeEvent.isCancelled()) continue;

                            switch (command.command) {
                                case "[webhook]" ->
                                        WatchNekoAPI.INSTANCE.getDiscordManager().sendAlert(player, verbose, check.getDisplayName(), violationCount);
                                case "[log]" -> {
                                    String verboseWithoutGl = verbose.replaceAll(" /gl .*", "");
                                    WatchNekoAPI.INSTANCE.getViolationDatabaseManager().logAlert(player, verboseWithoutGl, check.getDisplayName(), vl);
                                }
                                case "[proxy]" -> ProxyAlertMessenger.sendPluginMessage(cmd);
                                case "[alert]" -> {
                                    sentDebug = true;
                                    if (testMode) { // secret test mode
                                        if (verboseListeners == null || verboseListeners.contains(player.platformPlayer)) {
                                            player.sendMessage(interactive);
                                        }
                                    } else {
                                        WatchNekoAPI.INSTANCE.getAlertManager().sendAlert(interactive, verboseListeners);
                                    }
                                }
                                default ->
                                        WatchNekoAPI.INSTANCE.getScheduler().getGlobalRegionScheduler().run(WatchNekoAPI.INSTANCE.getGrimPlugin(), () ->
                                                WatchNekoAPI.INSTANCE.getPlatformServer().dispatchCommand(
                                                        WatchNekoAPI.INSTANCE.getPlatformServer().getConsoleSender(),
                                                        cmd
                                                )
                                        );
                            }
                        }

                        command.executeCount++;
                    }
                }
            }
        }

        return sentDebug;
    }

    public boolean handleViolation(Check check) {
        boolean containsCheck = false;
        for (PunishGroup group : groups) {
            if (group.checks.contains(check)) {
                long currentTime = System.currentTimeMillis();

                group.violations.put(currentTime, check);
                // Remove violations older than the defined time in the config
                group.violations.entrySet().removeIf(time -> currentTime - time.getKey() > group.removeViolationsAfter);
                containsCheck = true;
            }
        }
        return containsCheck;
    }

    private int getViolations(PunishGroup group, Check check) {
        int vl = 0;
        for (Check value : group.violations.values()) {
            if (value == check) vl++;
        }
        return vl;
    }
}

@RequiredArgsConstructor
class PunishGroup {
    public final List<AbstractCheck> checks;
    public final List<ParsedCommand> commands;
    public final Map<Long, Check> violations = new HashMap<>();
    public final int removeViolationsAfter; // time to remove violations after in milliseconds
}

@RequiredArgsConstructor
class ParsedCommand {
    public final int threshold;
    public final int interval;
    public final String command;
    public int executeCount;
}
