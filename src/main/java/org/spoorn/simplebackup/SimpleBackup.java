package org.spoorn.simplebackup;

import lombok.extern.log4j.Log4j2;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.WorldSavePath;
import org.spoorn.simplebackup.config.ModConfig;
import org.spoorn.simplebackup.mixin.MinecraftServerAccessor;
import org.spoorn.simplebackup.util.SimpleBackupUtil;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Log4j2
public class SimpleBackup implements ModInitializer {
    
    public static final String MODID = "simplebackup";
    public static final String BACKUPS_FOLDER = "backup";
    
    @Override
    public void onInitialize() {
        log.info("Hello from SimpleBackup!");
        
        // Config
        ModConfig.init();

        Path root = FabricLoader.getInstance().getGameDir();

        // Create worlds backup folder
        Path backupsPath = root.resolve(BACKUPS_FOLDER);
        SimpleBackupUtil.createDirectoryFailSafe(backupsPath);
        log.info("Worlds backup folder: {}", backupsPath);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (ModConfig.get().enableAutomaticBackups) {
                log.info("Automatic backups are enabled");
                MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
                String worldFolderName = accessor.getSession().getDirectoryName();
                Path worldSavePath = accessor.getSession().getDirectory(WorldSavePath.ROOT).getParent();

                int backupIntervals = ModConfig.get().backupIntervalInSeconds;
                log.info("Scheduling a backup every {} seconds...", backupIntervals);
                SimpleBackupTask simpleBackupTask = SimpleBackupTask.builder(root, worldFolderName, worldSavePath, server)
                        .backupIntervalInSeconds(backupIntervals)
                        .build();
                Thread backupThread = new Thread(simpleBackupTask);
                backupThread.start();
            }
        });
        
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (ModConfig.get().enableServerStoppedBackup) {
                log.info("Server has stopped - creating a backup");
                MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
                String worldFolderName = accessor.getSession().getDirectoryName();
                Path worldSavePath = accessor.getSession().getDirectory(WorldSavePath.ROOT).getParent();

                SimpleBackupTask simpleBackupTask = SimpleBackupTask.builder(root, worldFolderName, worldSavePath, server)
                                .build();
                simpleBackupTask.run();
            }
        });
    }
}
