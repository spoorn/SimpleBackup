package org.spoorn.simplebackup.util;

import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Log4j2
public class SimpleBackupUtil {
    
    public static void createDirectoryFailSafe(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            log.error(String.format("Failed to create %s folder", path), e);
        }
    }
    
    public static boolean copyDirectoriesFailSafe(Path source, Path destination) {
        String srcPathStr = source.toString();
        try {
            Files.walk(source)
                    .forEach(src -> {
                        Path dest = Path.of(destination.toString(), src.toString().substring(srcPathStr.length()));
                        try {
                            if (Files.notExists(dest)) {
                                Files.copy(source, dest);
                            }
                        } catch (IOException e) {
                            log.error(String.format("Could not copy file source=%s to destination=%s", src, dest), e);
                            throw new RuntimeException(e);
                        }
                    });
            return true;
        } catch (Exception e) {
            log.error(String.format("Could not copy directory from source=%s to destination=%s", source, destination), e);
            return false;
        }
    }
}
