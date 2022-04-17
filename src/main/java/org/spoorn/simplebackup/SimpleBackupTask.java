package org.spoorn.simplebackup;

import lombok.extern.log4j.Log4j2;
import net.minecraft.network.MessageType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.text.*;
import net.minecraft.util.Util;
import org.spoorn.simplebackup.util.SimpleBackupUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Log4j2
public class SimpleBackupTask implements Runnable {
    
    private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final MutableText BROADCAST1 = new TranslatableText("simplebackup.backup.broadcast1").setStyle(Style.EMPTY.withColor(13543679));
    private static final MutableText SUCCESS_BROADCAST = new TranslatableText("simplebackup.backup.success.broadcast");
    private static final MutableText FAILED_BROADCAST1 = new TranslatableText("simplebackup.backup.failed.broadcast1");
    private static final MutableText FAILED_BROADCAST2 = new TranslatableText("simplebackup.backup.failed.broadcast2");

    public final Object lock = new Object();
    private final Path root;
    private final String worldFolderName;
    private final Path worldSavePath;
    private final MinecraftServer server;
    private final long backupIntervalInMillis;
    
    private boolean terminated = false;

    SimpleBackupTask(Path root, String worldFolderName, Path worldSavePath, MinecraftServer server, int backupIntervalInSeconds) {
        this.root = root;
        this.worldFolderName = worldFolderName;
        this.worldSavePath = worldSavePath;
        this.server = server;
        this.backupIntervalInMillis = backupIntervalInSeconds * 1000L;
    }

    public static SimpleBackupTaskBuilder builder(final Path root, final String worldFolderName, final Path worldSavePath, 
                                                  final MinecraftServer server) {
        return new SimpleBackupTaskBuilder().root(root).worldFolderName(worldFolderName).worldSavePath(worldSavePath).server(server);
    }
    
    public void terminate() {
        this.terminated = true;
        this.lock.notify();
    }

    @Override
    public void run() {
        while (!terminated) {
            String timeStr = dtf.format(LocalDateTime.now());
            Path worldBackupPath = this.root.resolve(Path.of(SimpleBackup.BACKUPS_FOLDER, timeStr, this.worldFolderName));
            log.info("Backing up world [{}] to {}", this.worldFolderName, worldBackupPath);

            PlayerManager playerManager = this.server.getPlayerManager();
            playerManager.broadcast(BROADCAST1, MessageType.SYSTEM, Util.NIL_UUID);
            if (Files.exists(worldBackupPath)) {
                log.error("Backup at {} already exists!  Skipping...", worldBackupPath);
            }
            SimpleBackupUtil.createDirectoryFailSafe(worldBackupPath);

            boolean copied = SimpleBackupUtil.copyDirectoriesFailSafe(this.worldSavePath, worldBackupPath);
            Text relFolderPath = new LiteralText(timeStr + "/" + this.worldFolderName);
            if (copied) {
                playerManager.broadcast(SUCCESS_BROADCAST.copy().append(relFolderPath).setStyle(Style.EMPTY.withColor(8256183)), MessageType.SYSTEM, Util.NIL_UUID);
            } else {
                playerManager.broadcast(FAILED_BROADCAST1.copy().append(relFolderPath).append(FAILED_BROADCAST2).setStyle(Style.EMPTY.withColor(16754871)), MessageType.SYSTEM, Util.NIL_UUID);
            }
            
            if (this.backupIntervalInMillis > 1000) {
                // Automatic periodic backups
                try {
                    Thread.sleep(this.backupIntervalInMillis);
                } catch (InterruptedException e) {
                    log.error("SimpleBackupTask thread interrupted", e);
                }
            } else {
                // Single run
                break;
            }
        }
    }

    /**
     * Manual builder because lombok is stupid: https://github.com/projectlombok/lombok/issues/2307.
     */
    public static class SimpleBackupTaskBuilder {
        private Path root;
        private String worldFolderName;
        private Path worldSavePath;
        private MinecraftServer server;
        private int backupIntervalInSeconds = -1;

        SimpleBackupTaskBuilder() {
        }

        public SimpleBackupTaskBuilder root(Path root) {
            this.root = root;
            return this;
        }

        public SimpleBackupTaskBuilder worldFolderName(String worldFolderName) {
            this.worldFolderName = worldFolderName;
            return this;
        }

        public SimpleBackupTaskBuilder worldSavePath(Path worldSavePath) {
            this.worldSavePath = worldSavePath;
            return this;
        }

        public SimpleBackupTaskBuilder server(MinecraftServer server) {
            this.server = server;
            return this;
        }

        public SimpleBackupTaskBuilder backupIntervalInSeconds(int backupIntervalInSeconds) {
            this.backupIntervalInSeconds = Math.max(60, backupIntervalInSeconds);
            return this;
        }

        public SimpleBackupTask build() {
            return new SimpleBackupTask(root, worldFolderName, worldSavePath, server, backupIntervalInSeconds);
        }

        public String toString() {
            return "SimpleBackupTask.SimpleBackupTaskBuilder(root=" + this.root + ", worldFolderName=" + this.worldFolderName + ", worldSavePath=" + this.worldSavePath + ", server=" + this.server + ", backupIntervalInSeconds=" + this.backupIntervalInSeconds + ")";
        }
    }
}
