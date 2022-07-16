# Overview
Below, I'll summarize some of the technical aspects of this mod as I've learned a bit of tricks along the way which may be useful for anyone reading.

This Backup mod was built as a Minecraft Fabric mod which backs up the current world on the server at automatic intervals, with support for various formats, manual backups, and lots of configuration.

Though this is a Minecraft mod, the code here is general to file copying, backups, multi-threading, etc.  As I was using the mod on my own server, the world folder became very large (~70 GB) so I had to find ways to optimize the backup logic.  Here are my findings.

# Backup Formats

There are a few backup formats supported:

- ZIP (.zip)
- DIRECTORY (folder copy)
- LZ4 (.tar.lz4)

You can find a short description of each and when I recommend using which here: https://github.com/spoorn/SimpleBackup#backup-formats.

## ZIP

Most people should be familiar with the ZIP format.  ZIP is an **archive file format that supports lossless data compression**.  

When you zip a file, you are creating a .zip archive file, and on top of that, you can apply some data compression algorithm to the archive, such as the most commonly used DEFLATE algorithm.

An archive file is simply a singular file that contains one or more files/directories that can be nested.  It essentially allows you to take a directory, with all sub-directories and sub-files, and bundle it into a single "archive" file.  This is especially useful when you want to for example process a directory, transfer it over the wire (upload/download), etc. as it's easier to work with a single combined file in many cases than walking through an entire directory tree.

The [DEFLATE](https://en.wikipedia.org/wiki/Deflate) algorithm is the most commonly used compression algorithm for ZIP files, and is generally pretty good at reducing the size of your ZIP archive, thus allowing you to gain both the benefits of consolidating a directory tree into a single archive file, and having it be a smaller file size than it would be uncompressed.  This allows for faster upload/downloads, and it takes up less storage space.

ZIP files are so common, they are supported by most OS's natively.  If you take a ZIP file on Windows for example, by default, there's an option to extract it (aka uncompress) in the right-click menu.

There are many high-level libraries for ZIP which make it extremely [simple to code](https://github.com/spoorn/SimpleBackup/blob/8d462e642ff510f75e0725522d913d91aff035d0/src/main/java/org/spoorn/simplebackup/compressors/ZipCompressor.java#L14).  

To throw some numbers out there, using a 3.4 GHz 1-core CPU and a ~70 GB Minecraft world folder, zipping the folder took _**70 min**_, and using WinSCP to transfer the file from one host to another took a disgusting _**9 hours**_.

Let's do better...

## DIRECTORY

This is a simple copy of the directory.  Pretty self explanatory.  Convenient if only being used locally on a single disk for visual browsing via the folder explorer, but no compression, and no archiving.  Will have all the worst issues between ZIP and LZ4.  Copying files are faster however as there is no compression involved (which is why it's useful for local browsing only).

## LZ4

As my Minecraft server world grew larger and larger, the ZIP format was becoming an issue as backups on a light-weight host took over an hour... so I added support for LZ4!

LZ4 is a much newer lossless compression algorithm that is **much** (like ridiculously) faster than the DEFLATE algorithm, though does not compress as much, by default.  You can find the Java implementation of LZ4 here: https://github.com/lz4/lz4-java.

There are 2 steps that ZIP files go through:

1. Archive the targets (files + directories)
2. Compress

LZ4 handles the second step of compression.  For archiving, we can use [Tar](https://en.wikipedia.org/wiki/Tar_(computing)).

The LZ4 Java library requires us to glue some pipes together into this short pipeline:

```
FOREACH target file/directory:
  READ target file/directory => WRITE into a Tar Archive => WRITE into the LZ4 compression => WRITE to output .tar.lz4 file
```

High level steps (I'll provide code snippets later in the doc):
1. READ target file/directory: we can use the standard [FileInputStream](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/io/FileInputStream.html) for this
2. WRITE into a Tar Archive: [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/examples.html) has some libraries for this using a [TarArchiveOutputStream](https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/tar/TarArchiveOutputStream.html)
3. WRITE into the LZ4 compression: we use the [LZ4 Java library](https://github.com/lz4/lz4-java) to pipe from the TarArchiveOutputStream into a [LZ4FrameOutputStream](https://github.com/lz4/lz4-java/blob/a9c1b3a7d51115694ecee0976884f91cf9053d5a/src/java/net/jpountz/lz4/LZ4FrameOutputStream.java#L45)
4. WRITE to output .tar.lz4 file: we can use the standard [FileOutputStream](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/io/FileOutputStream.html)

Quick code snippet:

```java
/**
 * targetPath is target files/directories
 * destinationPath is the output file path
 */
public void tar(String targetPath, String destinationPath) throws IOException {
    // 3. WRITE into the LZ4 compression
    // 4. WRITE to output .tar.lz4 file
    // You can see it's literally like a pipeline of OutputStreams
    try (FileOutputStream fos = new FileOutputStream(destinationPath, true);
            LZ4FrameOutputStream outputStream = new LZ4FrameOutputStream(this.fos);
            CustomTarArchiveOutputStream taos = new CustomTarArchiveOutputStream(outputStream, this.slice == this.totalSlices - 1)) {

        addFilesToTar(targetPath, "", taos);
        taos.finish();  // Writes ending parts of the tar archive
    } catch (IOException e) {
        log.error("Failed to tar");
        throw e;
    }
}

// Base needed as we are branching off of a child directory, so the initial source will be the virtual "root" of the tar
private void addFilesToTar(String path, String base, TarArchiveOutputStream taos) throws IOException {
    File file = new File(path);
    // add tar ArchiveEntry
    String entryName = base + file.getName();
    taos.putArchiveEntry(new TarArchiveEntry(file, entryName));
    taos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);


    // 1. READ target file/directory
    // 2. WRITE into a Tar Archive
    if (file.isFile()) {
        try (FileInputStream fis = new FileInputStream(file)) {
            IOUtils.copy(fis, taos);  // Apache commons IOUtils.  It copies from an InputStream to an OutputStream
            taos.closeArchiveEntry();
        }
    } else {
        taos.closeArchiveEntry();
        for (File f : file.listFiles()) {
            // recurse for nested file/directories
            addFilesToTar(f.getPath(), entryName + File.separator, taos);
        }
    }
}
```

The code to compress a directory into a `.tar.lz4` archive is fairly short and straight forward.

With this alone, for a ~70 GB minecraft server, I was able to shorten the backup time on a 3.4 GHz 1-core CPU from **70 min** to **16 min**.  Tar archives also send over the wire much faster than ZIP archives for some reason.  Using WinSCP to transfer the `.tar.lz4` backup of my minecraft server went from a whopping **9 hours** to **30 min**.

HUGE improvement, but can we do even better?

## Multi-Threading with LZ4

TODO

