package org.spoorn.simplebackup.util;

import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

@Log4j2
public class SimpleBackupUtil {
    
    private static final Set<String> FILES_TO_SKIP_COPY = Set.of(
            "session.lock"
    );
    
    public static void createDirectoryFailSafe(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            log.error(String.format("Failed to create %s folder", path), e);
        }
    }
    
    public static boolean copyDirectoriesFailSafe(Path source, Path destination) {
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
}
