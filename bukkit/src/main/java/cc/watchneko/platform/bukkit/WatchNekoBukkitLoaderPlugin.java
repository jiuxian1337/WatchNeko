package cc.watchneko.platform.bukkit;

import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.plugin.BasicGrimPlugin;
import ac.grim.grimac.api.plugin.GrimPlugin;
import cc.watchneko.WatchNekoAPI;
import cc.watchneko.manager.init.Initable;
import cc.watchneko.manager.init.start.ExemptOnlinePlayersOnReload;
import cc.watchneko.manager.init.start.StartableInitable;
import cc.watchneko.platform.api.Platform;
import cc.watchneko.platform.api.PlatformLoader;
import cc.watchneko.platform.api.PlatformPlugin;
import cc.watchneko.platform.api.PlatformServer;
import cc.watchneko.platform.api.manager.*;
import cc.watchneko.platform.api.player.PlatformPlayerFactory;
import cc.watchneko.platform.api.scheduler.PlatformScheduler;
import cc.watchneko.platform.api.sender.Sender;
import cc.watchneko.platform.api.sender.SenderFactory;
import cc.watchneko.platform.bukkit.initables.BukkitBStats;
import cc.watchneko.platform.bukkit.initables.BukkitEventManager;
import cc.watchneko.platform.bukkit.initables.BukkitTickEndEvent;
import cc.watchneko.platform.bukkit.manager.*;
import cc.watchneko.platform.bukkit.player.BukkitPlatformPlayerFactory;
import cc.watchneko.platform.bukkit.scheduler.bukkit.BukkitPlatformScheduler;
import cc.watchneko.platform.bukkit.scheduler.folia.FoliaPlatformScheduler;
import cc.watchneko.platform.bukkit.sender.BukkitSenderFactory;
import cc.watchneko.platform.bukkit.utils.placeholder.PlaceholderAPIExpansion;
import cc.watchneko.utils.lazy.LazyHolder;
import com.github.retrooper.packetevents.PacketEventsAPI;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.brigadier.BrigadierSetting;
import org.incendo.cloud.brigadier.CloudBrigadierManager;
import org.incendo.cloud.bukkit.CloudBukkitCapabilities;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.incendo.cloud.setting.Configurable;
import org.jetbrains.annotations.NotNull;

public final class WatchNekoBukkitLoaderPlugin extends JavaPlugin implements PlatformLoader {

    public static WatchNekoBukkitLoaderPlugin LOADER;

    private final LazyHolder<PlatformScheduler> scheduler = LazyHolder.simple(this::createScheduler);
    private final LazyHolder<PacketEventsAPI<?>> packetEvents = LazyHolder.simple(() -> SpigotPacketEventsBuilder.build(this));
    private final LazyHolder<BukkitSenderFactory> senderFactory = LazyHolder.simple(BukkitSenderFactory::new);
    private final LazyHolder<CommandManager<Sender>> commandManager = LazyHolder.simple(this::createCommandManager);
    private final LazyHolder<ItemResetHandler> itemResetHandler = LazyHolder.simple(BukkitItemResetHandler::new);

    private final PlatformPlayerFactory playerFactory = new BukkitPlatformPlayerFactory();
    private final CommandAdapter parserFactory = new BukkitParserDescriptorFactory();
    private final PlatformPluginManager platformPluginManager = new BukkitPlatformPluginManager();
    private final GrimPlugin plugin;
    private final PlatformServer platformServer = new BukkitPlatformServer();
    private final MessagePlaceHolderManager messagePlaceHolderManager = new BukkitMessagePlaceHolderManager();
    private final BukkitPermissionRegistrationManager bukkitPermissionRegistrationManager = new BukkitPermissionRegistrationManager();

    public WatchNekoBukkitLoaderPlugin() {
        this.plugin = new BasicGrimPlugin(
                this.getLogger(),
                this.getDataFolder(),
                this.getDescription().getVersion(),
                this.getDescription().getDescription(),
                this.getDescription().getAuthors()
        );
    }

    @Override
    public void onLoad() {

        PlatformPlugin grimAC = this.getPluginManager().getPlugin("GrimAC");
        PlatformPlugin edGrim = this.getPluginManager().getPlugin("EdGrim");

        if (grimAC != null || edGrim != null) {
            this.getLogger().warning("检测到GrimAC或EdGrim，为防止冲突已关闭服务器");
            this.getLogger().warning("GrimAC or EdGrim detected – shutting down server to avoid conflicts");
            this.getServer().shutdown();
        }

        LOADER = this;
        WatchNekoAPI.INSTANCE.load(this, this.getBukkitInitTasks());
    }

    private Initable[] getBukkitInitTasks() {
        return new Initable[]{
                new ExemptOnlinePlayersOnReload(),
                new BukkitEventManager(),
                new BukkitTickEndEvent(),
                new BukkitBStats(),
                (StartableInitable) () -> {
                    if (BukkitMessagePlaceHolderManager.hasPlaceholderAPI) {
                        new PlaceholderAPIExpansion().register();
                    }
                }
        };
    }

    @Override
    public void onEnable() {
        WatchNekoAPI.INSTANCE.start();
    }

    @Override
    public void onDisable() {
        WatchNekoAPI.INSTANCE.stop();
    }

    @Override
    public PlatformScheduler getScheduler() {
        return scheduler.get();
    }

    @Override
    public PlatformPlayerFactory getPlatformPlayerFactory() {
        return playerFactory;
    }

    @Override
    public CommandAdapter getCommandAdapter() {
        return parserFactory;
    }

    @Override
    public PacketEventsAPI<?> getPacketEvents() {
        return packetEvents.get();
    }

    @Override
    public CommandManager<Sender> getCommandManager() {
        return commandManager.get();
    }

    @Override
    public ItemResetHandler getItemResetHandler() {
        return itemResetHandler.get();
    }

    @Override
    public SenderFactory<CommandSender> getSenderFactory() {
        return senderFactory.get();
    }

    @Override
    public GrimPlugin getPlugin() {
        return plugin;
    }

    @Override
    public PlatformPluginManager getPluginManager() {
        return platformPluginManager;
    }

    @Override
    public PlatformServer getPlatformServer() {
        return platformServer;
    }

    @Override
    public void registerAPIService() {
        GrimAPIProvider.init(WatchNekoAPI.INSTANCE.getExternalAPI());
        Bukkit.getServicesManager().register(GrimAbstractAPI.class, WatchNekoAPI.INSTANCE.getExternalAPI(), WatchNekoBukkitLoaderPlugin.LOADER, ServicePriority.Normal);
    }

    @Override
    public @NotNull MessagePlaceHolderManager getMessagePlaceHolderManager() {
        return messagePlaceHolderManager;
    }

    @Override
    public PermissionRegistrationManager getPermissionManager() {
        return bukkitPermissionRegistrationManager;
    }

    private PlatformScheduler createScheduler() {
        return WatchNekoAPI.INSTANCE.getPlatform() == Platform.FOLIA ? new FoliaPlatformScheduler() : new BukkitPlatformScheduler();
    }

    private CommandManager<Sender> createCommandManager() {
        LegacyPaperCommandManager<Sender> manager = new LegacyPaperCommandManager<>(
                this,
                ExecutionCoordinator.simpleCoordinator(),
                senderFactory.get()
        );
        if (manager.hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER)) {
            manager.registerBrigadier();
            CloudBrigadierManager<Sender, ?> cbm = manager.brigadierManager();
            Configurable<BrigadierSetting> settings = cbm.settings();
            settings.set(BrigadierSetting.FORCE_EXECUTABLE, true);
        } else if (manager.hasCapability(CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION)) {
            manager.registerAsynchronousCompletions();
        }
        return manager;
    }

    public BukkitSenderFactory getBukkitSenderFactory() {
        return LOADER.senderFactory.get();
    }
}
