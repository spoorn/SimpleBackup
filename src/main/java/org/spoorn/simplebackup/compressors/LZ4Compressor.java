package org.spoorn.simplebackup.compressors;

import lombok.extern.log4j.Log4j2;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.spoorn.simplebackup.SimpleBackup;
import org.spoorn.simplebackup.config.ModConfig;
import org.spoorn.simplebackup.io.CustomTarArchiveOutputStream;
import org.spoorn.simplebackup.util.SimpleBackupUtil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

@Log4j2
public class LZ4Compressor {
    
    public static final String TAR_LZ4_EXTENSION = ".tar.lz4";
    private static final String TMP_SUFFIX = ".tmp";
    private static boolean shouldLogBackupProgress = false;
    
    public static void init() {
        shouldLogBackupProgress = ModConfig.get().intervalPercentageToLogBackupProgress > 0 && ModConfig.get().intervalPercentageToLogBackupProgress <= 100;
    }
    
    // TODO: Add support for switching between fast vs high compressor    
    public static boolean compress(String targetPath, String destinationPath) {
        try {
            long fileCount = SimpleBackupUtil.fileCount(Path.of(targetPath));
            
            log.info("Backing up {} files using LZ4 compression", fileCount);
            int numThreads = ModConfig.get().numThreads;

            try (FileOutputStream outputFile = new FileOutputStream(destinationPath + TAR_LZ4_EXTENSION)) {
                if (numThreads < 2) {
                    new RunTarLZ4(targetPath, destinationPath, fileCount, 0, 1, outputFile).run();
                } else {
                    var futures = new Future[numThreads];
                    RunTarLZ4[] runnables = new RunTarLZ4[numThreads];

                    for (int i = 0; i < numThreads; i++) {
                        RunTarLZ4 runnable = new RunTarLZ4(targetPath, destinationPath, fileCount, i, numThreads, new FileOutputStream(destinationPath + "_" + i + TMP_SUFFIX));
                        futures[i] = SimpleBackup.EXECUTOR_SERVICE.submit(runnable);
                        runnables[i] = runnable;
                    }

                    // Logging progress for multi-threaded case
                    if (shouldLogBackupProgress) {
                        int currPercent;
                        int prevPercent = 0;
                        boolean isDone;
                        do {
                            currPercent = 0;
                            isDone = true;
                            for (int i = 0; i < numThreads; i++) {
                                currPercent += runnables[i].getPercentDone();
                                isDone &= futures[i].isDone();
                            }

                            // Dividing it here is valid as slices are balanced, else we'd have to return the # of files
                            // done from each task and divide by fileCount
                            currPercent /= numThreads;
                            int interval = ModConfig.get().intervalPercentageToLogBackupProgress;
                            if (prevPercent / interval < currPercent / interval) {
                                log.info("Backup progress: {}%", currPercent);
                            }
                            prevPercent = currPercent;
                            Thread.sleep(100);
                        } while (!isDone && prevPercent < 100);
                    }

                    // Wait for all futures to finish and write tmp files to the actual output file
                    // We use temp files to not hold backup bytes in memory causing OOM on the heap
                    for (int i = 0; i < numThreads; i++) {
                        futures[i].get();
                        runnables[i].fos.close();
                        String tmpFilePath = destinationPath + "_" + i + TMP_SUFFIX;
                        try (FileInputStream fis = new FileInputStream(tmpFilePath)) {
                            IOUtils.copy(fis, outputFile);
                        }
                        Files.deleteIfExists(Path.of(tmpFilePath));
                    }
                }

                return true;
            }
        } catch (Exception e) {
            log.error("Could not lz4 compress target=[" + targetPath + "] to [" + destinationPath + "]", e);
            return false;
        }
    }
    
    private static class RunTarLZ4 implements Runnable {
        
        private String targetPath;
        private String destinationPath;
        private long fileCount;
        private int slice;
        private int totalSlices;
        final FileOutputStream fos;
        
        private long pos;
        private long start;    // inclusive
        private long end;   // inclusive
        private int count;

        public RunTarLZ4(String targetPath, String destinationPath, long fileCount, int slice, int totalSlices, FileOutputStream fos) {
            this.targetPath = targetPath;
            this.destinationPath = destinationPath;
            this.fileCount = fileCount;
            this.slice = slice;
            this.totalSlices = totalSlices;
            this.fos = fos;

            /**
             * 1000 vs 999
             * 4
             * 250
             * 0 -> 249
             * 250 -> 499
             * 500 -> 749
             * 750 -> 999
             */

            long sliceLength = fileCount / totalSlices;
            this.start = sliceLength * slice;
            
            if (slice == totalSlices - 1) {
                this.end = fileCount - 1;  // for odd cases
            } else {
                this.end = sliceLength * (slice + 1) - 1;
            }
            this.pos = this.start;
            this.count = 0;
        }
        
        public int getPercentDone() {
            return (int) ((this.pos - this.start) * 100 / (this.end - this.start + 1));
        }

        @Override
        public void run() {
            try (LZ4FrameOutputStream outputStream = new LZ4FrameOutputStream(this.fos);
                 CustomTarArchiveOutputStream taos = new CustomTarArchiveOutputStream(outputStream, this.slice == this.totalSlices - 1)) {
                // Single case
                if (this.totalSlices == 1) {
                    addFilesToTar(targetPath, "", taos);
                } else {
                    log.info("Starting backup for slice {} with start={}, end={}", this.slice, this.start, this.end);
                    addFilesToTar(targetPath, "", taos);
                    log.info("Finished slice {}", this.slice);
                }
                
                taos.finish();
            } catch (IOException e) {
                log.error("Could not lz4 compress target=[" + targetPath + "] to [" + destinationPath + "] for slice " + slice, e);
                throw new RuntimeException(e);
            }
        }

        // Base needed as we are branching off of a child directory, so the initial source will be the virtual "root" of the tar
        private void addFilesToTar(String path, String base, TarArchiveOutputStream taos) throws IOException {
            File file = new File(path);
            if (!SimpleBackupUtil.FILES_TO_SKIP_COPY.contains(file.getName())) {
                // add tar ArchiveEntry
                
                // If we are out of bounds, skip
                // TODO: optimize
                if (file.isFile() && (count < this.start || count > this.end)) {
                    count++;
                    return;
                }

                String entryName = base + file.getName();

                if (file.isFile()) {
                    // Write file content to archive
                    try (FileInputStream fis = new FileInputStream(file)) {
                        taos.putArchiveEntry(new TarArchiveEntry(file, entryName));
                        taos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                        IOUtils.copy(fis, taos);
                        taos.closeArchiveEntry();
                        this.pos++;

                        // Logging progress for single-thread case
                        if (shouldLogBackupProgress && this.totalSlices == 1) {
                            int prevPercent = (int) (count * 100 / fileCount);
                            int currPercent = (int) ((count + 1) * 100 / fileCount);
                            int interval = ModConfig.get().intervalPercentageToLogBackupProgress;
                            if (prevPercent / interval < currPercent / interval) {
                                log.info("Backup progress: {}%", currPercent);
                            }
                        }
                        
                        count++;
                    }
                } else {
                    taos.putArchiveEntry(new TarArchiveEntry(file, entryName));
                    taos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                    taos.closeArchiveEntry();
                    for (File f : file.listFiles()) {
                        addFilesToTar(f.getPath(), entryName + File.separator, taos);
                    }
                }
            }
        }
    }
}
