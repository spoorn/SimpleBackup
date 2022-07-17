_This document is also cloned as a gist in case anyone wants to comment: https://gist.github.com/spoorn/e20ad021aaeca96835ea8d66c0e3923d_

# Overview
Below, I'll summarize some of the technical aspects of this mod as I've learned a bit of tricks along the way which may be useful for anyone reading.

This Backup mod was built as a Minecraft Fabric mod which backs up the current world on the server at automatic intervals, with support for various formats, manual backups, and lots of configuration.

Though this is a Minecraft mod, the code here is general to file copying, backups, multi-threading, etc.  As I was using the mod on my own server, the world folder became very large (~75 GB) so I had to find ways to optimize the backup logic.  Here are my findings.

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

To throw some numbers out there, using a 3.4 GHz 1-core CPU and a ~75 GB Minecraft world folder, zipping the folder took _**70 min**_, and using WinSCP to transfer the file from one host to another took a disgusting _**9 hours**_.  The compressed ZIP archive was ~65 GB.

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

With this alone, for a ~75 GB minecraft server, I was able to shorten the backup time on a 3.4 GHz 1-core CPU from **70 min** to **16 min**.  Tar archives also send over the wire much faster than ZIP archives.  Using WinSCP to transfer the `.tar.lz4` backup of my minecraft server went from a whopping **9 hours** to **30 min**.  The compressed `.tar.lz4` archive was ~69 GB.

HUGE improvement, but can we do even better?

## Multi-Threading with LZ4

Theoretically, there should be an obvious itch to use multi-threading in the `.tar.lz4` pipeline discussed above.  Running 75 GB of files/directories through a single pipeline is no way the most optimal we can do.  

Each file/directory in our target is added as an "Archive Entry" into the Tar archive, and then compressed using LZ4.  If we can use multi-threading to parallel process the target file/directories, and construct the final archive in a consolidated manner, the final output should be identical to what we would have if we used a single thread, but constructed N times faster based on how many threads/processors are available.

Let's break this down into parts we want to accomplish.  There are a few catches in each part, which I'll address below:

1. The input target files/directories need to be split and balanced among the threads.  This should be easy enough as we are simply walking through a directory tree to grab these input files.  We can either balanced on _number of files_, or _size in bytes_.  The latter should almost always be a better balance across threads, unless number of files becomes a bottleneck (i.e. one thread has to handle 1000s of files, while another thread only handles 1 in which case opening file streams could become the bottleneck).
2. Each target file/directory is added as an entry to the Tar archive, which is easy enough.  However, we would need to make sure if we are writing to the same TarArchiveOutputStream from multiple threads, that entries do not interweave due to concurrent modification
3. Each Tar Archive Entry is fed to the LZ4 compressor.  We have the same problem where we'd need to make sure each Tar Archive Entry is fed to the LZ4 compressor whole intact, else we'd have corrupted data.
4. Then the LZ4 compressed Tar Archive Entry is written to the output file.  This has the same problems as above

There's an obvious problem of concurrent modification where we need to make sure in this pipeline of OutputStreams, we do not interweave Tar Archive Entries and cause corruption.  A first guess solution could be to make part (2) synchronized - meaning we make sure we write each file/directory into the TarArchiveOutputStream as a whole atomic process, blocking all other threads from writing until it's done.  __But this defeats the purpose of trying to multi-thread the LZ4 process as we'd be processing each file/directory one by one!__

To truly be multi-threaded and the most optimal, we need to ensure each Thread can process its pipeline indepndent of all other Threads, without risk of corruption or concurrent modification issues.  If we were to use something like a ByteArrayOutputStream, so each Thread just holds its processed bytes in-memory, we will easily run out of Heap memory and the application will error out.  Instead...

The solution I went with: __temporary files.__

Each Thread will be given a **region** of the input target files/directories to process normally with nearly the exact code as the single thread case, but instead of writing to the same output file as other threads, it will write to a temporary file.  After all threads are done processing, we can merge all the temporary files generated into the final output file (which has some challenges which I'll break apart below).  You can imagine we are essentially giving each thread a **slice** or **region** of the input data to process, and a **slice** of the output file to write to.  This is now a true multi-threaded application.

Let's put it into play:

First, we need to figure out which slice of the input target files/directories each Thread gets:

```java
/**
 * Scans through a directory and finds the file count intervals, meaning the file number while walking through the
 * path, that split all the files evenly by size.  For balancing multi-threaded processing of a directory recursively. 
 * 
 * @param path Path to process
 * @param numIntervals Number of intervals, or number of threads
 * @return long[] that holds the file number indexes to split at
 * @throws IOException If processing files fail
 */
public static long[] getFileCountIntervalsFromSize(Path path, int numIntervals) throws IOException {
    long[] res = new long[numIntervals];
    // index of res, file count, current size, previous size
    long[] state = {1, 0, 0, 0};
    long sliceLength = getDirectorySize(path) / numIntervals;
    
    Files.walkFileTree(path, new SimpleFileVisitor<>() {

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (state[0] < res.length) {
                state[2] += attrs.size();
                if (state[3] / sliceLength < state[2] / sliceLength) {
                    res[(int) state[0]] = state[1];
                    state[0]++;
                }
                state[3] = state[2];
                state[1]++;
                return FileVisitResult.CONTINUE;
            } else {
                return FileVisitResult.TERMINATE;   
            }
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            state[1]++;
            return super.visitFileFailed(file, exc);
        }
    });
    return res;
}
```

This takes in a path and walks through the directory tree, scanning each file's size in bytes and splits the directory tree into `numIntervals` slices.  It outputs a `long[]` that holds indices of the directory tree walk that we should split at to balance size in bytes across the slices.

Note: this assumes that walking through the file tree is consistently ordered.

We'll also need the total file count for the directory tree to use later:

```java
public static long fileCount(Path path) throws IOException {
    return Files.walk(path)
            .filter(p -> !p.toFile().isDirectory())
            .count();
}
```

There is one quick catch we need to handle.  The TarArchiveOutputStream's `close()` method triggers the [`finish()`](https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/tar/TarArchiveOutputStream.html#finish--) method, which writes the Tar Archive footers and EOF.  We want to make sure this is only written by the thread that is handling the **last** slice.  We can simply extend the TarArchiveOutputStream with our own custom TAOS that only triggers `finish()` if it knows its for the last slice:

```java
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
        // If this isn't for the last slice, do nothing
    }
}
```

Now let's put this together and create the Runnable that each thread will run:


```java
public static class RunTarLZ4 implements Runnable {
        
    private final String targetPath;  // target input path
    private final String destinationPath;  // destination output file i.e. the temporary file this thread will write to
    private final long fileCount;  // Total number of files in our target path
    private final int slice;  // The slice we are looking at, indexed at 0
    private final int totalSlices;  // The total number of slices.  Used to know if we are on the last slice to write the Tar Archive footers
    final FileOutputStream fos;  // Output Stream to the file output for this task
    
    private final long start;    // inclusive
    private final long end;   // exclusive
    private int count;  // Current file number this task is processing

    public RunTarLZ4(String targetPath, String destinationPath, long fileCount, long start, long end, int slice, int totalSlices, FileOutputStream fos) {
        this.targetPath = targetPath;
        this.destinationPath = destinationPath;
        this.fileCount = fileCount;
        this.slice = slice;
        this.totalSlices = totalSlices;
        this.fos = fos;

        this.start = start;
        this.end = end;
        this.count = 0;
    }

    @Override
    public void run() {
        try (LZ4FrameOutputStream outputStream = new LZ4FrameOutputStream(this.fos);
                CustomTarArchiveOutputStream taos = new CustomTarArchiveOutputStream(outputStream, this.slice == this.totalSlices - 1)) {
            
            log.info("Starting backup for slice {} with start={}, end={}", this.slice, this.start, this.end - 1);
            addFilesToTar(targetPath, "", taos);
            log.info("Finished compressed archive for slice {}", this.slice);
            
            taos.finish();
        } catch (IOException e) {
            log.error("Could not lz4 compress target=[" + targetPath + "] to [" + destinationPath + "] for slice " + slice, e);
            throw new RuntimeException(e);
        }
    }

    // Base needed as we are branching off of a child directory, so the initial source will be the virtual "root" of the tar
    private void addFilesToTar(String path, String base, TarArchiveOutputStream taos) throws IOException {
        File file = new File(path);
            
        // If we are out of the bounds of our slice, skip
        // This could probably be optimized to not have to walk through the entire file tree again.
        // Instead, we could have cached the exact files each slice should handle.
        // It's a trade off between using more memory, or more processing steps
        if (file.isFile() && (count < this.start || count >= this.end)) {
            count++;
            return;
        }

        String entryName = base + file.getName();

        // Add the Tar Archive Entry
        taos.putArchiveEntry(new TarArchiveEntry(file, entryName));
        taos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

        if (file.isFile()) {
            // Write file content to archive
            try (FileInputStream fis = new FileInputStream(file)) {
                IOUtils.copy(fis, taos, ModConfig.get().multiThreadBufferSize);
                taos.closeArchiveEntry();
                
                count++;
            }
        } else {
            taos.closeArchiveEntry();
            for (File f : file.listFiles()) {
                // Recurse on nested files/directories
                addFilesToTar(f.getPath(), entryName + File.separator, taos);
            }
        }
    }
}
```

This is nearly the same code as previously in the single threaded use case!  The only differences are:

1. We put the code into a Runnable class, and made a lot of the state fields of the class for easier access
2. Each Runnable needs to keep track of what file number in the directory tree walk it's currently processing (`count`), and what the bounds of its slice (`start` - `end`) it should handle.  This is to make sure each Thread only handles what it was assigned to handle, and not step on other threads' shoes.
3. Each Runnable has the `FileOutputStream fos` that it will be writing to.  Based on what we decided above, each Runnable should be writing to a temporary `.tmp` file that we will later merge into the final output `.tar.lz4` file.
4. All the other new variables such as `slice`, `totalSlices`, etc. are for logging purposes!

Now we have a Runnable that each Thread will run to write some input slice into a `.tmp` file.  The final step is to merge those `.tmp` files into the single final output `.tar.lz4` file.

There are multiple ways in which we can copy each `.tmp` file into the final output file:
- standard IO Streams
- NIO2
- Apache Commons IOUtils, or other libraries (which use uses standard IO streams or NIO2 under the hood)
- FileChannels

From online benchmarks, [FileChannels](https://docs.oracle.com/javase/7/docs/api/java/nio/channels/FileChannel.html) are by far the most efficient in writing contents of one file, into another.  This is because FileChannels can use the Operating System to [write directly from the file system cache to the output file](https://docs.oracle.com/javase/7/docs/api/java/nio/channels/FileChannel.html#transferTo(long,%20long,%20java.nio.channels.WritableByteChannel)).

Though FileChannels technically are thread-safe, we need to make sure each Thread is able to write to a specific position in the file, without affecting other threads' positions, and avoid closing the FileChannels too early.  This can be done via an [AsynchronousFileChannel](https://docs.oracle.com/javase/7/docs/api/java/nio/channels/AsynchronousFileChannel.html).  This is exactly what we need for each thread to write to a file at a certain position, completely independent of the other threads.

We can take a hybrid approach and use FileChannels to read from each .tmp file, and use the AsynchronousFileChannel to write to the final output file from each Thread.

Let's put everything together now:

```java
public static boolean compress(String targetPath, String destinationPath) {
    try {
        // Number of threads for multi-threading.  Set to 1 for single-thread processing
        // You can play around with this number to optimize for your use case
        int numThreads = 4; 

        // We'll submit our runnable tasks using an executor service with `numThreads` threads in the pool
        final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(numThreads);

        // Get our file count
        long fileCount = fileCount(Path.of(targetPath));
        
        // Get the file number intervals we discussed earlier
        long[] fileNumIntervals = getFileCountIntervalsFromSize(Path.of(targetPath), numThreads);

        if (numThreads < 2) {
            // In the single-threaded case, we simply write directly to the final output file
            FileOutputStream outputFile = new FileOutputStream(destinationPath + TAR_LZ4_EXTENSION, true);
            new RunTarLZ4(targetPath, destinationPath, fileCount, 0, fileCount, 0, 1, outputFile).run();
            outputFile.close();
        } else {
            // In the multi-threaded use case, we'll spin up `numThreads` threads, each writing to its own temporary file
            var futures = new Future[numThreads];
            RunTarLZ4[] runnables = new RunTarLZ4[numThreads];

            for (int i = 0; i < numThreads; i++) {
                // Spin up a thread for each Runnable task

                // start of the slice is from our fileNumIntervals
                long start = fileNumIntervals[i];   

                // end of the slice is 1 before the next fileNumInterval, or if we are on the last slice
                // we can simply set this to the fileCount to cover the rest of the files
                long end = i == numThreads - 1 ? fileCount : fileNumIntervals[i + 1];   

                // Each Runnable task will be outputting to a temporary file, which is the same name as the output file except
                // suffixed with "_sliceNum.tmp"
                FileOutputStream tmpOutputFile = new FileOutputStream(destinationPath + "_" + i + ".tmp");

                RunTarLZ4 runnable = new RunTarLZ4(targetPath, destinationPath, fileCount, start, end, i, numThreads, tmpOutputFile);

                // Save a reference to each Thread Future, and the Runnable so we can properly close() or clean them up later
                futures[i] = EXECUTOR_SERVICE.submit(runnable);
                runnables[i] = runnable;
            }

            // Wait for all futures to finish
            for (int i = 0; i < numThreads; i++) {
                futures[i].get();
                runnables[i].fos.close();   // Clean up and close the .tmp file OutputStreams
            }
            
            // At this point, we have all our .tmp files which are standalone .tar.lz4 compressed archives for each  slice
            // The .tmp files can't be opened themselves however, as they are a sliced part of the final output file.
            // Here, we can now merge all the .tmp files we created, into the single final output file
            // There are multiple ways to merge files into one, such as Streams, NIO2, Apache Commons, etc.
            // From other benchmarks online, the most efficient way to do this is via FileChannels, which can use the
            // underlying OS and data caches to copy files closer to the hardware, giving us the fastest results.

            // Another thing to make note of is, we NEED to make sure we are writing to the final output file in parallel
            // across the multiple threads, otherwise this merging of .tmp files becomes a bottleneck!
            // This is made possible with the AsynchronousFileChannel API, which allows for writing bytes directly into a file
            // at some specified offset position.

            FileInputStream[] tmpFiles = new FileInputStream[numThreads];
            FileChannel[] tmpChannels = new FileChannel[numThreads];
            long[] fileChannelOffsets = new long[numThreads];
            
            // This grabs a FileChannel to read for each .tmp file, and also calculates what all the fileChannel position offsets
            // we should use for each Thread, based on the size in bytes of each .tmp file
            for (int i = 0; i < numThreads; i++) {
                tmpFiles[i] = new FileInputStream(destinationPath + "_" + i + TMP_SUFFIX);
                tmpChannels[i] = tmpFiles[i].getChannel();
                if (i < numThreads - 1) {
                    fileChannelOffsets[i + 1] = fileChannelOffsets[i] + tmpChannels[i].size();
                }
            }
            
            // Create an AsynchronousFileChannel for the final output `.tar.lz4` file
            // This channel is has the capability to WRITE to the file, or CREATE it if it doesn't yet exist
            AsynchronousFileChannel destChannel = AsynchronousFileChannel.open(Path.of(destinationPath + ".tar.lz4"), WRITE, CREATE);
            for (int i = 0; i < numThreads; i++) {
                int finalI = i;
                // Let's again spin up a thread for each .tmp file to write to its slice, or region in the final output file
                futures[i] = EXECUTOR_SERVICE.submit(() -> {
                    try {
                        log.info("Writing region for backup slice {}", finalI);
                        String tmpFilePath = destinationPath + "_" + finalI + ".tmp";

                        // You can play around with the buffer size to optimize
                        ByteBuffer buf = ByteBuffer.allocate(8192);

                        // Let's get our FileChannel which we opened earlier, for the .tmp file
                        FileChannel tmpChannel = tmpChannels[finalI];
                    
                        // The position in the output file this thread will be writing to
                        long pos = fileChannelOffsets[finalI];
                        int read;
                        while ((read = tmpChannel.read(buf)) != -1) {
                            buf.flip();
                            // Write bytes from the .tmp file to the offset position in the final output file
                            destChannel.write(buf, pos).get();
                            // Update our position for this thread
                            pos += read;
                            buf.clear();
                        }

                        // Clean up everything, including deleting the .tmp file now that we finished writing it all to the
                        // final output file
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

            // Done!
            destChannel.close();
        }

        return true;
    } catch (Exception e) {
        log.error("Could not lz4 compress target=[" + targetPath + "] to [" + destinationPath + "]", e);
        return false;
    }
}
```

Great!  We now have an application that is able to spin up threads to take in slices of the input data, compress and archive each file/directory using Tar Archive and LZ4 compression, then output the slices into a final output `.tar.lz4` file that is identical as if we did it in the single threaded use case.  We made sure every step of the pipeline is multi-threaded properly, especially the final step of merging `.tmp` files into the `.tar.lz4` output.

With this multi-threaded processing, on the ~75 GB minecraft world folder using the same Intel Xeon Processor with 3.4 GHz and 1-Core, `numThreads=4` and `bufferSize=8192`, I was able to drop the archiving time from standard LZ4's **16 minutes** to **4.5 minutes**, with the exactly same output file compressed to **69 GB**, and takes the same time to download it via WinSCP.


# Conclusion
For a ~75 GB Minecraft world folder, Intel Xeon processor 3.4 GHz, 1-Core

|Format|Time to Archive + Compress|Time to send over WinSCP|Compressed Size|
|:---:|:---:|:---:|:---:|
|ZIP|70 minutes|9 hours|~65 GB|
|TAR + LZ4|16 minutes|30 minutes|~69 GB|
|Multi-threaded TAR + LZ4|4.5 minutes|30 minutes|~69 GB|

TAR + LZ4 was multiple times faster than ZIP for archive + compress, and sending over the wire, though a few GB larger compressed size.  Multi-threading the TAR + LZ4 algorithm was able to even futher drop the Time to archive + compress by multiple times, almost linearly with respect to number of threads.

I found similar results testing this on my own standard Ryzen 5 5600X which has 3.7 GHz slock speed, and 6-cores, though it was already pretty fast with a ~75 GB directory.  The larger the input target directory you want to archive + compress, the more beneficial multi-threading can be - so long as you have the processing power and cores to do it!
