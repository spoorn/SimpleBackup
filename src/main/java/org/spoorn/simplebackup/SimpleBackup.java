package org.spoorn.simplebackup;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;
import com.mojang.brigadier.arguments.StringArgumentType;
import lombok.extern.log4j.Log4j2;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.MessageType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Style;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import net.minecraft.util.WorldSavePath;
import org.spoorn.simplebackup.config.ModConfig;
import org.spoorn.simplebackup.mixin.MinecraftServerAccessor;
import org.spoorn.simplebackup.util.SimpleBackupUtil;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Log4j2
public class SimpleBackup implements ModInitializer {
    
    public static final String MODID = "simplebackup";
    public static final String BACKUPS_FOLDER = "backup";
    private static final AtomicReference<SimpleBackupTask> manualBackupTask = new AtomicReference<>();
    
    @Override
    public void onInitialize() {
        log.info("Hello from SimpleBackup!");
        
        // Config
        ModConfig.init();
        
        // Lang for backup broadcast messages
        SimpleBackupTask.init();

        Path root = FabricLoader.getInstance().getGameDir();

        // Create worlds backup folder
        Path backupsPath = root.resolve(BACKUPS_FOLDER);
        SimpleBackupUtil.createDirectoryFailSafe(backupsPath);
        log.info("Worlds backup folder: {}", backupsPath);

        // Automatic backups
        final boolean enableAutomaticBackups = ModConfig.get().enableAutomaticBackups;
        AtomicReference<SimpleBackupTask> simpleBackupTask = new AtomicReference<>();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (enableAutomaticBackups) {
                log.info("Automatic backups are enabled");
                MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
                String worldFolderName = accessor.getSession().getDirectoryName();
                Path worldSavePath = accessor.getSession().getDirectory(WorldSavePath.ROOT).getParent();

                int backupIntervals = ModConfig.get().backupIntervalInSeconds;
                log.info("Scheduling a backup every {} seconds...", Math.max(60, backupIntervals));
                simpleBackupTask.set(SimpleBackupTask.builder(root, worldFolderName, worldSavePath, server)
                        .backupIntervalInSeconds(backupIntervals)
                        .build());
                Thread backupThread = new Thread(simpleBackupTask.get());
                backupThread.start();
            }
        });
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SimpleBackupTask autoBackup;
            if ((autoBackup = simpleBackupTask.get()) != null) {
                if (autoBackup.isProcessing && autoBackup.lastBackupProcessed != null) {
                    SimpleBackupUtil.cleanupFailedBackup(autoBackup.lastBackupProcessed);
                }
            }
        }));

        // Notify backup thread when player joins server
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            SimpleBackupTask autoBackup;
            if (enableAutomaticBackups && (autoBackup = simpleBackupTask.get()) != null) {
                synchronized (autoBackup.lock) {
                    autoBackup.lock.notify();
                }
            }
        });
        
        // Backup when server is stopped
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            SimpleBackupTask autoBackup;
            if (enableAutomaticBackups && (autoBackup = simpleBackupTask.get()) != null) {
                autoBackup.terminate();
            }
            
            if (ModConfig.get().enableServerStoppedBackup) {
                log.info("Server has stopped - creating a backup");
                MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
                String worldFolderName = accessor.getSession().getDirectoryName();
                Path worldSavePath = accessor.getSession().getDirectory(WorldSavePath.ROOT).getParent();

                SimpleBackupTask serverStopBackup = SimpleBackupTask.builder(root, worldFolderName, worldSavePath, server)
                                .build();
                serverStopBackup.run();

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (serverStopBackup.isProcessing && serverStopBackup.lastBackupProcessed != null) {
                        SimpleBackupUtil.cleanupFailedBackup(serverStopBackup.lastBackupProcessed);
                    }
                }));
            }
        });
        
        // Commands
        Map<String, String> broadcastMessages = ModConfig.get().broadcastMessages;
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            dispatcher.register(literal("simplebackup")
                    .then(literal("start")
                        .executes(c -> {
                            try {
                                ServerCommandSource commandSource = c.getSource();
                                synchronized (manualBackupTask) {
                                    if (manualBackupTask.get() != null) {
                                        commandSource.sendFeedback(new LiteralText(broadcastMessages.getOrDefault("simplebackup.manualbackup.alreadyexists",
                                                "There is already an ongoing manual backup.  Please wait for it to finish before starting another!"))
                                                .setStyle(Style.EMPTY.withColor(16433282)), true);
                                    } else {
                                        commandSource.getServer().getPlayerManager().broadcast(
                                                c.getSource().getPlayer().getDisplayName().copy().append(
                                                        new LiteralText(broadcastMessages.getOrDefault("simplebackup.manualbackup.started",
                                                                " triggered a manual backup"))
                                                                .setStyle(Style.EMPTY.withColor(16433282))), MessageType.SYSTEM, Util.NIL_UUID);

                                        MinecraftServer server = commandSource.getServer();
                                        MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
                                        String worldFolderName = accessor.getSession().getDirectoryName();
                                        Path worldSavePath = accessor.getSession().getDirectory(WorldSavePath.ROOT).getParent();

                                        SimpleBackupTask serverStopBackup = SimpleBackupTask.builder(root, worldFolderName, worldSavePath, server)
                                                .build();
                                        manualBackupTask.set(serverStopBackup);
                                        new Thread(() -> {
                                            serverStopBackup.run();
                                            manualBackupTask.set(null);
                                        }).start();
                                    }
                                }
                                return 1;
                            } catch (Exception e) {
                                log.error("Could not create manual backup!", e);
                                return 0;
                            }
                        })));
        });
    }
}
