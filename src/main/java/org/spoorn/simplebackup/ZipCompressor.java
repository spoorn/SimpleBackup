package org.spoorn.simplebackup;

import lombok.extern.log4j.Log4j2;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ExcludeFileFilter;
import net.lingala.zip4j.model.ZipParameters;
import org.spoorn.simplebackup.util.SimpleBackupUtil;

import java.io.File;

@Log4j2
public class ZipCompressor {
    
    public static final String ZIP_EXTENSION = ".zip";
    
    public static boolean zip(String targetPath, String destinationPath) {
        try {
            ExcludeFileFilter excludeFileFilter = file -> {
                return SimpleBackupUtil.FILES_TO_SKIP_COPY.contains(file.getName());
            };
            ZipParameters parameters = new ZipParameters();
            parameters.setExcludeFileFilter(excludeFileFilter);

            ZipFile zipFile = new ZipFile(destinationPath + ZIP_EXTENSION);
            zipFile.setRunInThread(false);

            File targetFile = new File(targetPath);
            if (targetFile.isDirectory()) {
                zipFile.addFolder(targetFile, parameters);
            } else if (targetFile.isFile()) {
                zipFile.addFile(targetFile, parameters);
            } else {
                throw new IllegalArgumentException("Target Path=" + targetPath + " is not a valid file or directory to backup");
            }
            
            return true;
        } catch (Exception e) {
            log.error("Could not zip target=[" + targetPath + "] to [" + destinationPath + "]", e);
            return false;
        }
    }
}
