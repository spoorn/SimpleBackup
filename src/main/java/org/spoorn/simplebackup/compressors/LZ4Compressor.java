package org.spoorn.simplebackup.compressors;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import lombok.extern.log4j.Log4j2;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.spoorn.simplebackup.SimpleBackup;
import org.spoorn.simplebackup.config.ModConfig;
import org.spoorn.simplebackup.io.CustomTarArchiveOutputStream;
import org.spoorn.simplebackup.util.SimpleBackupUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
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
            int numThreads = Math.max(ModConfig.get().numThreads, 1);
            long[] fileCountIntervals = SimpleBackupUtil.getFileCountIntervalsFromSize(Path.of(targetPath), numThreads);

            // append flag set to true for multi-threaded speed
            if (numThreads < 2) {
                FileOutputStream outputFile = new FileOutputStream(destinationPath + TAR_LZ4_EXTENSION, true);
                new RunTarLZ4(targetPath, destinationPath, fileCount, 0, fileCount, 0, 1, outputFile).run();
                outputFile.close();
            } else {
                var futures = new Future[numThreads];
                RunTarLZ4[] runnables = new RunTarLZ4[numThreads];

                for (int i = 0; i < numThreads; i++) {
                    RunTarLZ4 runnable = new RunTarLZ4(targetPath, destinationPath, fileCount, fileCountIntervals[i], 
                            i == numThreads - 1 ? fileCount : fileCountIntervals[i + 1], i, numThreads, 
                            new FileOutputStream(destinationPath + "_" + i + TMP_SUFFIX));
                    futures[i] = SimpleBackup.EXECUTOR_SERVICE.submit(runnable);
                    runnables[i] = runnable;
                }

                // Logging progress for multi-threaded case, also waits for future to finish
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

                // Wait for all futures to finish
                for (int i = 0; i < numThreads; i++) {
                    futures[i].get();
                    runnables[i].fos.close();
                }
                
                FileInputStream[] tmpFiles = new FileInputStream[numThreads];
                FileChannel[] tmpChannels = new FileChannel[numThreads];
                long[] fileChannelOffsets = new long[numThreads];
                
                for (int i = 0; i < numThreads; i++) {
                    tmpFiles[i] = new FileInputStream(destinationPath + "_" + i + TMP_SUFFIX);
                    tmpChannels[i] = tmpFiles[i].getChannel();
                    if (i < numThreads - 1) {
                        fileChannelOffsets[i + 1] = fileChannelOffsets[i] + tmpChannels[i].size();
                    }
                }
                
                // Wait for all futures to finish and write tmp files to the actual output file
                // We use temp files to not hold backup bytes in memory causing OOM on the heap
                // This needs to be multi-threaded as well to avoid bottlenecking on file copying
                AsynchronousFileChannel destChannel = AsynchronousFileChannel.open(Path.of(destinationPath + TAR_LZ4_EXTENSION), WRITE, CREATE);
                log.info("Combining sliced backup files into single compressed archive...");
                // TODO: Merging progress monitor
                for (int i = 0; i < numThreads; i++) {
                    int finalI = i;
                    futures[i] = SimpleBackup.EXECUTOR_SERVICE.submit(() -> {
                        try {
                            log.info("Writing region for backup slice {}", finalI);
                            String tmpFilePath = destinationPath + "_" + finalI + TMP_SUFFIX;

                            ByteBuffer buf = ByteBuffer.allocate(ModConfig.get().multiThreadBufferSize);
                            FileChannel tmpChannel = tmpChannels[finalI];
                            int read;
                            long pos = fileChannelOffsets[finalI];
                            while ((read = tmpChannel.read(buf)) != -1) {
                                buf.flip();
                                destChannel.write(buf, pos).get();
                                pos += read;
                                buf.clear();
                            }

                            tmpChannel.close();
                            tmpFiles[finalI].close();
                            Files.deleteIfExists(Path.of(tmpFilePath));
                            log.info("Finished writing region for backup slice {}", finalI);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }

                // Wait for all futures to finish
                for (int i = 0; i < numThreads; i++) {
                    futures[i].get();
                }

                destChannel.close();
            }

            return true;
        } catch (Exception e) {
            log.error("Could not lz4 compress target=[" + targetPath + "] to [" + destinationPath + "]", e);
            return false;
        }
    }
    
    private static class RunTarLZ4 implements Runnable {
        
        private final String targetPath;
        private final String destinationPath;
        private final long fileCount;
        private final int slice;
        private final int totalSlices;
        final FileOutputStream fos;
        
        private long pos;
        private final long start;    // inclusive
        private final long end;   // exclusive
        private int count;

        public RunTarLZ4(String targetPath, String destinationPath, long fileCount, long start, long end, int slice, int totalSlices, FileOutputStream fos) {
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

            this.start = start;
            this.end = end;
            this.pos = this.start;
            this.count = 0;
        }
        
        public int getPercentDone() {
            return (int) ((this.pos - this.start) * 100 / (this.end - this.start));
        }

        @Override
        public void run() {
            try (LZ4FrameOutputStream outputStream = new LZ4FrameOutputStream(this.fos);
                 CustomTarArchiveOutputStream taos = new CustomTarArchiveOutputStream(outputStream, this.slice == this.totalSlices - 1)) {
                // Single case
                if (this.totalSlices == 1) {
                    addFilesToTar(targetPath, "", taos);
                } else {
                    log.info("Starting backup for slice {} with start={}, end={}", this.slice, this.start, this.end - 1);
                    addFilesToTar(targetPath, "", taos);
                    log.info("Finished compressed archive for slice {}", this.slice);
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
                if (file.isFile() && (count < this.start || count >= this.end)) {
                    count++;
                    return;
                }

                String entryName = base + file.getName();

                if (file.isFile()) {
                    // Write file content to archive
                    try (FileInputStream fis = new FileInputStream(file)) {
                        taos.putArchiveEntry(new TarArchiveEntry(file, entryName));
                        taos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                        IOUtils.copy(fis, taos, ModConfig.get().multiThreadBufferSize);
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
