package org.spoorn.simplebackup.compressors;

import lombok.extern.log4j.Log4j2;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.spoorn.simplebackup.util.SimpleBackupUtil;

import java.io.*;
import java.nio.file.Path;

@Log4j2
public class LZ4Compressor {
    
    public static final String TAR_LZ4_EXTENSION = ".tar.lz4";
    
    // TODO: Add support for switching between fast vs high compressor    
    public static boolean compress(String targetPath, String destinationPath) {
        try {
            LZ4FrameOutputStream outputStream = new LZ4FrameOutputStream(new FileOutputStream(destinationPath + TAR_LZ4_EXTENSION));
            tar(targetPath, outputStream);
            outputStream.close();
            return true;
        } catch (IOException e) {
            log.error("Could not lz4 compress target=[" + targetPath + "] to [" + destinationPath + "]", e);
            return false;
        }
    }
    
    public static void tar(String targetPath, OutputStream outputStream) throws IOException {
        try (TarArchiveOutputStream aos = new TarArchiveOutputStream(outputStream)) {
            addFilesToTar(targetPath, "", aos);
            aos.finish();
        }
    }
    
    // Base needed as we are branching off of a child directory, so the initial source will be the virtual "root" of the tar
    private static void addFilesToTar(String path, String base, TarArchiveOutputStream aos) throws IOException {
        File file = new File(path);
        if (!SimpleBackupUtil.FILES_TO_SKIP_COPY.contains(file.getName())) {
            // add tar ArchiveEntry
            String entryName = base + file.getName();
            aos.putArchiveEntry(new TarArchiveEntry(file, entryName));
            aos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            if (file.isFile()) {
                // Write file content to archive
                try (FileInputStream fis = new FileInputStream(file)) {
                    IOUtils.copy(fis, aos);
                    aos.closeArchiveEntry();
                }
            } else {
                aos.closeArchiveEntry();
                for (File f : file.listFiles()) {
                    addFilesToTar(f.getPath(), entryName + File.separator, aos);
                }
            }
        }
    }
}
