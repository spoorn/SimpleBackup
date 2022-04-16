package org.spoorn.simplebackup;

import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.spoorn.simplebackup.util.SimpleBackupUtil;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@AllArgsConstructor
@Log4j2
public class SimpleBackupTask implements Runnable {

    private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    
    private final Path root;
    private final String worldFolderName;
    private final Path worldSavePath;

    @Override
    public void run() {
        // Do a backup
        String timeStr = dtf.format(LocalDateTime.now());
        Path worldBackupPath = root.resolve(Path.of(SimpleBackup.BACKUPS_FOLDER, timeStr, worldFolderName));
        log.info("Backing up world [{}] to {}", worldFolderName, worldBackupPath);
        SimpleBackupUtil.createDirectoryFailSafe(worldBackupPath);
        boolean copied = SimpleBackupUtil.copyDirectoriesFailSafe(worldSavePath, worldBackupPath);
    }
}
