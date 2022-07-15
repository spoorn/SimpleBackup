package org.spoorn.simplebackup.compressors;

import lombok.extern.log4j.Log4j2;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ExcludeFileFilter;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.progress.ProgressMonitor;
import org.spoorn.simplebackup.config.ModConfig;
import org.spoorn.simplebackup.util.SimpleBackupUtil;

import java.io.File;

@Log4j2
public class ZipCompressor {
    
    public static final String ZIP_EXTENSION = ".zip";

    private static boolean shouldLogBackupProgress = false;

    public static void init() {
        shouldLogBackupProgress = ModConfig.get().intervalPercentageToLogBackupProgress > 0 && ModConfig.get().intervalPercentageToLogBackupProgress <= 100;
    }
    
    public static boolean zip(String targetPath, String destinationPath) {
        try {
            ExcludeFileFilter excludeFileFilter = file -> SimpleBackupUtil.FILES_TO_SKIP_COPY.contains(file.getName());
            ZipParameters parameters = new ZipParameters();
            parameters.setExcludeFileFilter(excludeFileFilter);

            ZipFile zipFile = new ZipFile(destinationPath + ZIP_EXTENSION);
            
            if (shouldLogBackupProgress) {
                zipFile.setRunInThread(true);
            }
            
            ProgressMonitor progressMonitor = zipFile.getProgressMonitor();

            File targetFile = new File(targetPath);
            if (targetFile.isDirectory()) {
                zipFile.addFolder(targetFile, parameters);
            } else if (targetFile.isFile()) {
                zipFile.addFile(targetFile, parameters);
            } else {
                throw new IllegalArgumentException("Target Path=" + targetPath + " is not a valid file or directory to backup");
            }
            
            if (shouldLogBackupProgress) {
                int prevPercent = 0;
                int interval = ModConfig.get().intervalPercentageToLogBackupProgress;
                while (progressMonitor.getState() != ProgressMonitor.State.READY) {
                    int currPercent = progressMonitor.getPercentDone();
                    if (prevPercent / interval < currPercent / interval) {
                        log.info("Backup progress: {}%", currPercent);
                    }
                    prevPercent = currPercent;
                    Thread.sleep(100);
                }
            }
            
            return true;
        } catch (Exception e) {
            log.error("Could not zip target=[" + targetPath + "] to [" + destinationPath + "]", e);
            return false;
        }
    }
}
