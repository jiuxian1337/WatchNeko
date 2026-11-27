package cc.watchneko;

import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.alerts.AlertManager;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.event.EventBus;
import ac.grim.grimac.api.event.events.GrimReloadEvent;
import cc.watchneko.cloud.CloudGateway;
import cc.watchneko.manager.config.ConfigManagerFileImpl;
import cc.watchneko.manager.init.start.StartableInitable;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.LogUtil;
import cc.watchneko.utils.anticheat.MessageUtil;
import cc.watchneko.utils.common.ConfigReloadObserver;
import lombok.Getter;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

//This is used for grim's external API. It has its own class just for organization.

public class WatchNekoExternalAPI implements GrimAbstractAPI, ConfigReloadObserver, StartableInitable {

    private final WatchNekoAPI api;
    @Getter
    private final Map<String, Function<GrimUser, String>> variableReplacements = new ConcurrentHashMap<>();
    @Getter
    private final Map<String, String> staticReplacements = new ConcurrentHashMap<>();
    private final Map<String, Function<Object, Object>> functions = new ConcurrentHashMap<>();
    private final ConfigManagerFileImpl configManagerFile = new ConfigManagerFileImpl();
    private ConfigManager configManager = null;
    private boolean started = false;

    public WatchNekoExternalAPI(WatchNekoAPI api) {
        this.api = api;
    }

    @Override
    public @NonNull EventBus getEventBus() {
        return api.getEventBus();
    }

    @Override
    public @Nullable GrimUser getGrimUser(UUID uuid) {
        return api.getPlayerDataManager().getPlayer(uuid);
    }

    @Override
    public void registerVariable(String string, Function<GrimUser, String> replacement) {
        if (replacement == null) {
            variableReplacements.remove(string);
        } else {
            variableReplacements.put(string, replacement);
        }
    }

    @Override
    public void registerVariable(String variable, String replacement) {
        if (replacement == null) {
            staticReplacements.remove(variable);
        } else {
            staticReplacements.put(variable, replacement);
        }
    }

    @Override
    public String getGrimVersion() {
        return api.getGrimPlugin().getDescription().getVersion();
    }

    @Override
    public void registerFunction(String key, Function<Object, Object> function) {
        if (function == null) {
            functions.remove(key);
        } else {
            functions.put(key, function);
        }
    }

    @Override
    public Function<Object, Object> getFunction(String key) {
        return functions.get(key);
    }

    @Override
    public AlertManager getAlertManager() {
        return WatchNekoAPI.INSTANCE.getAlertManager();
    }

    @Override
    public ConfigManager getConfigManager() {
        return configManager;
    }

    @Override
    public boolean hasStarted() {
        return started;
    }

    @Override
    public int getCurrentTick() {
        return WatchNekoAPI.INSTANCE.getTickManager().currentTick;
    }

    // on load, load the config & register the service
    public void load() {
        reload(configManagerFile);
        api.getLoader().registerAPIService();
    }

    // handles any config loading that's needed to be done after load
    @Override
    public void start() {
        started = true;
        try {
            WatchNekoAPI.INSTANCE.getConfigManager().start();
        } catch (Exception e) {
            LogUtil.error("Failed to start config manager.", e);
        }
    }

    @Override
    public void reload(ConfigManager config) {
        if (config.isLoadedAsync() && started) {
            WatchNekoAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(WatchNekoAPI.INSTANCE.getGrimPlugin(),
                    () -> successfulReload(config));
        } else {
            successfulReload(config);
        }
    }

    @Override
    public CompletableFuture<Boolean> reloadAsync(ConfigManager config) {
        if (config.isLoadedAsync() && started) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            WatchNekoAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(WatchNekoAPI.INSTANCE.getGrimPlugin(),
                    () -> future.complete(successfulReload(config)));
            return future;
        }
        return CompletableFuture.completedFuture(successfulReload(config));
    }

    private boolean successfulReload(ConfigManager config) {
        try {
            config.reload();
            WatchNekoAPI.INSTANCE.getConfigManager().load(config);
            if (started) WatchNekoAPI.INSTANCE.getConfigManager().start();
            onReload(config);
            if (started)
                WatchNekoAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(WatchNekoAPI.INSTANCE.getGrimPlugin(),
                        () -> WatchNekoAPI.INSTANCE.getEventBus().post(new GrimReloadEvent(true)));
            return true;
        } catch (Exception e) {
            LogUtil.error("Failed to reload config", e);
        }
        if (started)
            WatchNekoAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(WatchNekoAPI.INSTANCE.getGrimPlugin(),
                    () -> WatchNekoAPI.INSTANCE.getEventBus().post(new GrimReloadEvent(false)));
        return false;
    }

    @Override
    public void onReload(ConfigManager newConfig) {
        if (newConfig == null) {
            LogUtil.warn("ConfigManager not set. Using default config file manager.");
            configManager = configManagerFile;
        } else {
            configManager = newConfig;
        }
        // Update variables
        updateVariables();
        // Restart
        WatchNekoAPI.INSTANCE.getAlertManager().reload(configManager);
        WatchNekoAPI.INSTANCE.getDiscordManager().reload();
        WatchNekoAPI.INSTANCE.getSpectateManager().reload();
        WatchNekoAPI.INSTANCE.getViolationDatabaseManager().reload();
        // Rebuild the cloud gateway before players reload their checks, so a check either sees a fully
        // configured gateway or none at all
        CloudGateway.reload(configManager);
        // Recordings outlive a disconnect; this keeps their paused state in step with the connection.
        cc.watchneko.cloud.RecordingLifecycle.install();
        // Don't reload players if the plugin hasn't started yet
        if (!started) return;
        // Reload checks for all players
        for (PlayerData player : WatchNekoAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            player.runSafely(() -> player.reload(configManager));
        }
    }

    private void updateVariables() {
        variableReplacements.putIfAbsent("%player%", GrimUser::getName);
        variableReplacements.putIfAbsent("%uuid%", user -> user.getUniqueId().toString());
        variableReplacements.putIfAbsent("%ping%", user -> user.getTransactionPing() + "");
        variableReplacements.putIfAbsent("%brand%", GrimUser::getBrand);
        variableReplacements.putIfAbsent("%client%", GrimUser::getBrand);
        variableReplacements.putIfAbsent("%h_sensitivity%", user -> ((int) Math.round(user.getHorizontalSensitivity() * 200)) + "");
        variableReplacements.putIfAbsent("%v_sensitivity%", user -> ((int) Math.round(user.getVerticalSensitivity() * 200)) + "");
        variableReplacements.putIfAbsent("%fast_math%", user -> !user.isVanillaMath() + "");
        variableReplacements.putIfAbsent("%tps%", user -> {
            double tps = WatchNekoAPI.INSTANCE.getPlatformServer().getTPS();
            return Double.isFinite(tps) ? String.format("%.2f", tps) : "N/A";
        });
        variableReplacements.putIfAbsent("%version%", GrimUser::getVersionName);
        // static variables
        staticReplacements.put("%prefix%", MessageUtil.translateAlternateColorCodes('&', WatchNekoAPI.INSTANCE.getConfigManager().getPrefix()));
        staticReplacements.putIfAbsent("%grim_version%", getGrimVersion());
    }
}
