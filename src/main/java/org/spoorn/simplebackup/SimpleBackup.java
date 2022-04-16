package org.spoorn.simplebackup;

import lombok.extern.log4j.Log4j2;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.WorldSavePath;
import org.spoorn.simplebackup.mixin.MinecraftServerAccessor;
import org.spoorn.simplebackup.util.SimpleBackupUtil;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Log4j2
public class SimpleBackup implements ModInitializer {
    
    public static final String MODID = "simplebackup";
    private static final String BACKUPS_FOLDER = "backup";
    private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadScheduledExecutor();
    
    @Override
    public void onInitialize() {
        log.info("Hello from SimpleBackup!");

        Path root = FabricLoader.getInstance().getGameDir();

        // Create worlds backup folder
        Path backupsPath = root.resolve(BACKUPS_FOLDER);
        SimpleBackupUtil.createDirectoryFailSafe(backupsPath);
        log.info("Worlds backup folder: {}", backupsPath);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
            String worldFolderName = accessor.getSession().getDirectoryName();
            Path worldSavePath = accessor.getSession().getDirectory(WorldSavePath.ROOT).getParent();

            // Do a backup
            String timeStr = dtf.format(LocalDateTime.now());
            Path worldBackupPath = root.resolve(Path.of(BACKUPS_FOLDER, timeStr, worldFolderName));
            log.info("Backing up world [{}] to {}", worldFolderName, worldBackupPath);
            SimpleBackupUtil.createDirectoryFailSafe(worldBackupPath);
            boolean copied = SimpleBackupUtil.copyDirectoriesFailSafe(worldSavePath, worldBackupPath);
        });
    }
}
