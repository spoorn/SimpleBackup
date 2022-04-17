package org.spoorn.simplebackup;

import lombok.AllArgsConstructor;
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

@AllArgsConstructor
@Log4j2
public class SimpleBackupTask implements Runnable {

    private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final MutableText BROADCAST1 = new TranslatableText("simplebackup.backup.broadcast1").setStyle(Style.EMPTY.withColor(13543679));
    private static final MutableText SUCCESS_BROADCAST = new TranslatableText("simplebackup.backup.success.broadcast");
    private static final MutableText FAILED_BROADCAST1 = new TranslatableText("simplebackup.backup.failed.broadcast1");
    private static final MutableText FAILED_BROADCAST2 = new TranslatableText("simplebackup.backup.failed.broadcast2");

    private final Path root;
    private final String worldFolderName;
    private final Path worldSavePath;
    private final MinecraftServer server;

    @Override
    public void run() {
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
    }
}
