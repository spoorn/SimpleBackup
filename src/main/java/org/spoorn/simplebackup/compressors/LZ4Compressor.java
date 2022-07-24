package org.spoorn.simplebackup.compressors;

import lombok.extern.log4j.Log4j2;
import org.spoorn.simplebackup.config.ModConfig;
import org.spoorn.simplebackup.util.SimpleBackupUtil;
import org.spoorn.tarlz4java.api.TarLz4Compressor;
import org.spoorn.tarlz4java.api.TarLz4CompressorBuilder;
import org.spoorn.tarlz4java.logging.Verbosity;
import org.spoorn.tarlz4java.util.concurrent.NamedThreadFactory;

import java.util.concurrent.Executors;

@Log4j2
public class LZ4Compressor {
    
    public static final String TAR_LZ4_EXTENSION = ".tar.lz4";
    private static boolean shouldLogBackupProgress = false;
    
    public static void init() {
        shouldLogBackupProgress = ModConfig.get().intervalPercentageToLogBackupProgress > 0 && ModConfig.get().intervalPercentageToLogBackupProgress <= 100;
    }
    
    // TODO: Add support for switching between fast vs high compressor    
    public static boolean compress(String targetPath, String destinationPath, String outputFileBaseName) {
        try {
            int numThreads = ModConfig.get().numThreads;
            TarLz4Compressor compressor = new TarLz4CompressorBuilder()
                    .numThreads(numThreads)
                    .bufferSize(ModConfig.get().multiThreadBufferSize)
                    .logProgressPercentInterval(ModConfig.get().intervalPercentageToLogBackupProgress)
                    .executorService(Executors.newFixedThreadPool(numThreads, new NamedThreadFactory("SimpleBackup")))
                    .shouldLogProgress(shouldLogBackupProgress)
                    .verbosity(Verbosity.DEBUG)
                    .excludeFiles(SimpleBackupUtil.FILES_TO_SKIP_COPY)
                    .build();
            return compressor.compress(targetPath, destinationPath, outputFileBaseName) != null;
        } catch (Exception e) {
            log.error("Could not lz4 compress target=[" + targetPath + "] to [" + destinationPath + "]", e);
            return false;
        }
    }
}
