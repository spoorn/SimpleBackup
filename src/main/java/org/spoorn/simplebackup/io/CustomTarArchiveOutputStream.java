package org.spoorn.simplebackup.io;

import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.IOException;
import java.io.OutputStream;

/**
 * For multi-threaded tar archiving in LZ4Compressor, if we are not on the last slice, don't write the archive end entries.
 */
public class CustomTarArchiveOutputStream extends TarArchiveOutputStream {
    
    private final boolean isLast;
    
    public CustomTarArchiveOutputStream(OutputStream os, boolean isLast) {
        super(os);
        this.isLast = isLast;
    }

    @Override
    public void finish() throws IOException {
        if (this.isLast) {
            super.finish();
        }
    }
}
