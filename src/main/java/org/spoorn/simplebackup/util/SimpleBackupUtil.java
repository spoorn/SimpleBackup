package org.spoorn.simplebackup.util;

import lombok.extern.log4j.Log4j2;
import org.spoorn.simplebackup.SimpleBackup;
import org.spoorn.simplebackup.ZipCompressor;
import org.spoorn.simplebackup.config.ModConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Log4j2
public class SimpleBackupUtil {
    
    public static final String ZIP_FORMAT = "ZIP";
    public static final String DIRECTORY_FORMAT = "DIRECTORY";
    public static final Set<String> FILES_TO_SKIP_COPY = Set.of(
            "session.lock"
    );
    
    public static void createDirectoryFailSafe(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            log.error(String.format("Failed to create %s folder", path), e);
        }
    }
    
    public static boolean backup(Path source, String worldFolderName, Path gameDir, String timeStr) {
        if (!checkAvailableSpace(source, gameDir)) {
            return false;
        }
        
        String backupFormat = ModConfig.get().backupFormat;
        if (ZIP_FORMAT.equals(backupFormat)) {
            Path destination = gameDir.resolve(Path.of(SimpleBackup.BACKUPS_FOLDER, timeStr));
            String destinationFile = destination + ZipCompressor.ZIP_EXTENSION;
            log.info("Backing up world [{}] to {}", source, destinationFile);
            if (Files.exists(Path.of(destinationFile))) {
                log.error("Backup at {} already exists!  Skipping...", destinationFile);
            }
            return ZipCompressor.zip(source.toString(), destination.toString());
        } else if (DIRECTORY_FORMAT.equals(backupFormat)) {
            Path destination = gameDir.resolve(Path.of(SimpleBackup.BACKUPS_FOLDER, timeStr, worldFolderName));
            log.info("Backing up world [{}] to {}", source, destination);
            if (Files.exists(destination)) {
                log.error("Backup at {} already exists!  Skipping...", destination);
            }
            createDirectoryFailSafe(destination);
            return copyDirectoriesFailSafe(source, destination);
        } else {
            log.error("SimpleBackup config 'backupFormat'={} is not supported!", backupFormat);
            return false;
        }
    }
    
    private static boolean checkAvailableSpace(Path source, Path gameDir) {
        File partition = gameDir.toFile();
        double availableDiskSpace = ((double) partition.getUsableSpace()) / partition.getTotalSpace() * 100;
        if (availableDiskSpace < ModConfig.get().percentageAvailableDiskSpaceRequirement) {
            log.error(String.format("Not enough available disk space to create backup! Disk space available: %.2f%%.  " +
                    "Config's percentageAvailableDiskSpaceRequirement: %d", availableDiskSpace, ModConfig.get().percentageAvailableDiskSpaceRequirement));
            return false;
        }
        
        File sourceFile = source.toFile();
        // Make sure we have enough space for the backup itself.  Adds a buffer of 5% as ZIP files could be larger in size
        if (sourceFile.length() > (partition.getTotalSpace() - partition.getUsableSpace()) * 1.05) {
            log.error(String.format("Backup size may exceed the available disk space!  Please clear out your disk space before generating\n" +
                    "another backup.  Disk space available: %.2f%%.  You need at least %d bytes free before we can make more backups.", 
                    availableDiskSpace, sourceFile.length()));
            return false;
        }
        return true;
    }
    
    public static boolean deleteStaleBackupFiles(Path gameDir) {
        File[] backupFiles = gameDir.resolve(SimpleBackup.BACKUPS_FOLDER).toFile().listFiles();
        if (backupFiles != null) {
            int numBackupFiles = backupFiles.length;
            AtomicBoolean errorWhileSorting = new AtomicBoolean(false);
            int maxBackupsTokeep = ModConfig.get().maxBackupsToKeep;
            if (numBackupFiles >= maxBackupsTokeep) {
                Arrays.sort(backupFiles, Comparator.comparingLong(file -> {
                    try {
                        return Files.readAttributes(file.toPath(), BasicFileAttributes.class).creationTime().toMillis();
                    } catch (IOException e) {
                        log.error("Error while sorting backup files by creationTime", e);
                        errorWhileSorting.set(true);
                        return 0;
                    }
                }));

                if (errorWhileSorting.get()) {
                    return false;
                }
            }
            while (numBackupFiles > maxBackupsTokeep) {
                try {
                    Path fileToDelete = backupFiles[backupFiles.length - numBackupFiles].toPath();
                    log.info("Deleting backup at [{}] as we have more backups than maxBackupsToKeep={}", fileToDelete, maxBackupsTokeep);
                    Files.walkFileTree(fileToDelete, new SimpleFileVisitor<>() {
                        
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            Files.delete(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                            Files.delete(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                            Files.delete(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                    numBackupFiles--;
                } catch (Exception e) {
                    log.error("Could not check if number of backup files exceeds the maxBackupsToKeep", e);
                    return false;
                }
            }
        }
        return true;
    }
    
    private static boolean copyDirectoriesFailSafe(Path source, Path destination) {
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    Files.createDirectories(destination.resolve(source.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Path dest = destination.resolve(source.relativize(file));
                    if (!FILES_TO_SKIP_COPY.contains(file.getFileName().toString()) && Files.notExists(dest)) {
                        Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            
            return true;
        } catch (Exception e) {
            log.error(String.format("Could not copy directory from source=%s to destination=%s", source, destination), e);
            return false;
        }
    }
    
    public static void cleanupFailedBackup(Path backupPath) {
        try {
            log.info("Attempting to cleanup interrupted backup at {}", backupPath);
            Files.deleteIfExists(backupPath);
        } catch (Exception e) {
            log.error("Could not cleanup interrupted backup process", e);
        }
    }
}
