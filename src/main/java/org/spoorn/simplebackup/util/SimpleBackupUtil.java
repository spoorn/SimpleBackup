package org.spoorn.simplebackup.util;

import lombok.extern.log4j.Log4j2;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.PlayerManager;
import net.minecraft.text.Text;
import org.apache.commons.io.filefilter.NotFileFilter;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.spoorn.simplebackup.compressors.LZ4Compressor;
import org.spoorn.simplebackup.compressors.ZipCompressor;
import org.spoorn.simplebackup.config.ModConfig;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Log4j2
public class SimpleBackupUtil {
    
    public static final String ZIP_FORMAT = "ZIP";
    public static final String DIRECTORY_FORMAT = "DIRECTORY";
    public static final String LZ4_FORMAT = "LZ4";
    public static final Set<String> FILES_TO_SKIP_COPY = Set.of(
            "session.lock"
    );
    private static final NotFileFilter EXCLUDE_FILES = new NotFileFilter(new SuffixFileFilter(".tmp"));
    
    public static void createDirectoryFailSafe(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            log.error(String.format("Failed to create %s folder", path), e);
        }
    }
    
    public static Path getBackupPath() {
        String backupPath = ModConfig.get().backupPath;
        Path p = Paths.get(backupPath);
        if (p.isAbsolute()) {
            return p;
        } else {
            Path root = FabricLoader.getInstance().getGameDir();
            return root.resolve(backupPath);
        }
    }
    
    public static void broadcastMessage(Text message, PlayerManager playerManager) {
        if (ModConfig.get().broadcastBackupMessage) {
            playerManager.broadcast(message, false);
        }
    }

    public static long fileCount(Path path) throws IOException {
        return Files.walk(path)
                .filter(p -> !p.toFile().isDirectory())
                .count();
    }
    
    public static long getDirectorySize(Path path) throws IOException {
        AtomicLong size = new AtomicLong();

        // Ignores failed files, should be fine for our use case
        Files.walkFileTree(path, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                size.addAndGet(attrs.size());
                return FileVisitResult.CONTINUE;
            }
        });
        
        return size.get();
    }

    /**
     * Scans through a directory and finds the file count intervals, meaning the file number while walking through the
     * path, that split all the files evenly by size.  For balancing multi-threaded processing of a directory recursively. 
     * 
     * @param path Path to process
     * @param numIntervals Number of intervals
     * @return long[] that holds the file number indexes to split at
     * @throws IOException If processing files fail
     */
    public static long[] getFileCountIntervalsFromSize(Path path, int numIntervals) throws IOException {
        long[] res = new long[numIntervals];
        // index of res, file count, current size, previous size
        long[] state = {1, 0, 0, 0};
        long sliceLength = getDirectorySize(path) / numIntervals;
        
        Files.walkFileTree(path, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (state[0] < res.length) {
                    state[2] += attrs.size();
                    if (state[3] / sliceLength < state[2] / sliceLength) {
                        res[(int) state[0]] = state[1];
                        state[0]++;
                    }
                    state[3] = state[2];
                    state[1]++;
                    return FileVisitResult.CONTINUE;
                } else {
                    return FileVisitResult.TERMINATE;   
                }
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                state[1]++;
                return super.visitFileFailed(file, exc);
            }
        });
        return res;
    }
    
    public static boolean backup(Path source, String worldFolderName, String timeStr, String backupFormat) {
        if (!checkAvailableSpace(source)) {
            return false;
        }
        
        if (ZIP_FORMAT.equals(backupFormat)) {
            Path destination = getBackupPath().resolve(timeStr);
            String destinationFile = destination + ZipCompressor.ZIP_EXTENSION;
            log.info("Backing up world [{}] to {}", source, destinationFile);
            if (Files.exists(Path.of(destinationFile))) {
                log.error("Backup at {} already exists!  Skipping...", destinationFile);
            }
            return ZipCompressor.zip(source.toString(), destination.toString());
        } if (LZ4_FORMAT.equals(backupFormat)) {
            Path destination = getBackupPath().resolve(timeStr);
            String destinationFile = destination + LZ4Compressor.TAR_LZ4_EXTENSION;
            log.info("Backing up world [{}] to {}", source, destinationFile);
            if (Files.exists(Path.of(destinationFile))) {
                log.error("Backup at {} already exists!  Skipping...", destinationFile);
            }
            return LZ4Compressor.compress(source.toString(), destination.getParent().toString(), timeStr);
        } else if (DIRECTORY_FORMAT.equals(backupFormat)) {
            Path destination = getBackupPath().resolve(Path.of(timeStr, worldFolderName));
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
    
    private static boolean checkAvailableSpace(Path source) {
        File partition = getBackupPath().toFile();
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
    
    public static boolean deleteStaleBackupFiles() {
        File[] backupFiles = getBackupPath().toFile().listFiles((FilenameFilter) EXCLUDE_FILES);
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
            final long fileCount = fileCount(source);
            final int interval = ModConfig.get().intervalPercentageToLogBackupProgress;
            AtomicReference<Integer> atomicCount = new AtomicReference<>(0);
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

                        int count = atomicCount.get();  // Not thread safe
                        int prevPercent = (int) ((float) count / fileCount * 100);
                        count++;
                        int currPercent = (int) ((float) count / fileCount * 100);
                        if (prevPercent / interval < currPercent / interval) {
                            log.info("Backup progress: {}%", currPercent);
                        }
                        atomicCount.set(count);
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
