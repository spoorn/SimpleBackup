package org.spoorn.simplebackup;

import lombok.extern.log4j.Log4j2;
import net.minecraft.network.MessageType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Util;
import org.spoorn.simplebackup.config.ModConfig;
import org.spoorn.simplebackup.util.SimpleBackupUtil;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Log4j2
public class SimpleBackupTask implements Runnable {
    
    private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final Text BROADCAST1 = Text.of(new TranslatableText("simplebackup.backup.broadcast1").setStyle(Style.EMPTY.withColor(13543679)).getString());
    private static final Text SUCCESS_BROADCAST = Text.of(new TranslatableText("simplebackup.backup.success.broadcast").getString());
    private static final Text FAILED_BROADCAST1 = Text.of(new TranslatableText("simplebackup.backup.failed.broadcast1").getString());
    private static final Text FAILED_BROADCAST2 = Text.of(new TranslatableText("simplebackup.backup.failed.broadcast2").getString());

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
        synchronized (this.lock) {
            this.lock.notify();
        }
    }

    @Override
    public void run() {
        PlayerManager playerManager = this.server.getPlayerManager();

        // wait at start
        if (!terminated && this.backupIntervalInMillis > 1000) {
            waitToContinue(playerManager);
        }
        
        // Automatic backup loops
        while (!terminated) {
            String timeStr = dtf.format(LocalDateTime.now());
            playerManager.broadcast(BROADCAST1, MessageType.SYSTEM, Util.NIL_UUID);

            boolean copied = SimpleBackupUtil.backup(this.worldSavePath, this.worldFolderName, this.root, timeStr);
            String broadcastBackupPath = SimpleBackupUtil.ZIP_FORMAT.equals(ModConfig.get().backupFormat) ?
                    timeStr + ".zip" : timeStr + "/" + this.worldFolderName;
            Text relFolderPath = new LiteralText(broadcastBackupPath);
            if (copied) {
                log.info("Successfully backed up world [{}] to [{}]", this.worldFolderName, broadcastBackupPath);
                playerManager.broadcast(SUCCESS_BROADCAST.copy().append(relFolderPath).setStyle(Style.EMPTY.withColor(8060843)), MessageType.SYSTEM, Util.NIL_UUID);
            } else {
                log.error("Server backup for world [{}] failed!  Check the logs for errors.", this.worldFolderName);
                playerManager.broadcast(FAILED_BROADCAST1.copy().append(relFolderPath).append(FAILED_BROADCAST2).setStyle(Style.EMPTY.withColor(16754871)), MessageType.SYSTEM, Util.NIL_UUID);
            }
            
            if (this.backupIntervalInMillis > 1000) {
                waitToContinue(playerManager);
            } else {
                // Single run
                break;
            }
        }
    }
    
    private void waitToContinue(PlayerManager playerManager) {
        // Automatic periodic backups
        try {
            // Technically there is an extremely small window where all server players can log out between the
            // backup and this check, so we'll never backup that window.  But it's small enough to not worry about practically
            // The below logic to wait on the lock will simply wait if we just backed up, but there are no players
            // online, or the single player game is paused.  This does mean the next backup's changed content
            // might span a duration less than the backup intervals, but this is intended as I think it's better
            // than trying to make sure each backup has an exact "online running" difference from the previous.
            if (ModConfig.get().onlyBackupIfPlayersOnline && playerManager.getCurrentPlayerCount() == 0) {
                // Wait until a player logs on
                synchronized (this.lock) {
                    this.lock.wait();
                }
            }

            Thread.sleep(this.backupIntervalInMillis);
        } catch (InterruptedException e) {
            log.error("SimpleBackupTask thread interrupted", e);
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
