package main;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

// indexing progress status
public class FileCrawler {

    private static final String UP_AND_ERASE_LINE = "\033[A\033[2K";
    private static final boolean ERRORS = false;

    private static class FileSystemStatistics {

        public String name;
        public AtomicLong totalFiles = new AtomicLong();
        public AtomicLong hiddenFiles = new AtomicLong();
        public AtomicLong executableFiles = new AtomicLong();
        public AtomicLong readableFiles = new AtomicLong();
        public AtomicLong writableFiles = new AtomicLong();
        public AtomicLong numberOfFiles = new AtomicLong();
        public AtomicLong numberOfDirectories = new AtomicLong();
        public AtomicLong maxFileSizeBytes = new AtomicLong();
        public AtomicLong totalFilesSizeBytes = new AtomicLong();
        public AtomicLong maxFilesInDir = new AtomicLong();
        public AtomicReference<String> maxFileSizeName = new AtomicReference<>();
        public long startTimeMilli = -1;
        public long endTimeMilli = -1;
        public float timeTakenSec = -1;
        public volatile boolean isFinished;
        public List<String> errors = Collections.synchronizedList(new LinkedList<String>());

        public FileSystemStatistics(String name) {
            this.name = requireNonNull(name);
        }

        @Override
        public String toString() {
            return """
            %s:
                isFinished          = %s
                timeTaken           = %s sec
                filesTotal          = %s
                files per second    = %.2f
                hiddenFiles         = %s
                executableFiles     = %s
                readableFiles       = %s
                writableFiles       = %s
                numberOfFiles       = %s
                numberOfDirectories = %s
                maxFileSizeBytes    = %s
                maxFileSizeName     = %.15s
                totalFileSizeBytes  = %s
                maxFilesInDir       = %s
            """
                    .formatted(
                            name,
                            isFinished,
                            timeTakenSec,
                            totalFiles.get(),
                            totalFiles.get()
                                    / (((endTimeMilli != -1
                                                            ? endTimeMilli
                                                            : System.currentTimeMillis())
                                                    - startTimeMilli)
                                            / 1000f),
                            hiddenFiles.get(),
                            executableFiles.get(),
                            readableFiles.get(),
                            writableFiles.get(),
                            numberOfFiles.get(),
                            numberOfDirectories.get(),
                            maxFileSizeBytes.get(),
                            maxFileSizeName.get(),
                            totalFilesSizeBytes.get(),
                            maxFilesInDir.get());
        }
    }

    public static void main(String[] args) throws InterruptedException {
        var fssSingleThreaded = new FileSystemStatistics("Single Threaded");
        var fssDoubleThreaded = new FileSystemStatistics("Double Threaded");
        var fssMultiThreaded = new FileSystemStatistics("Multi Threaded");

        new Thread(
                        () -> {
                            try {
                                var arr = fssSingleThreaded.toString().toCharArray();

                                var lines = 0;
                                for (int i = 0; i < arr.length; i++) {
                                    if (arr[i] == '\n') {
                                        lines++;
                                    }
                                }

                                // lines * number of thread statics sets + a line break for each set
                                // + counter
                                lines = lines * 3 + 3 + 1;
                                int secondsCoounter = 0;

                                System.out.println("Seconds Passed: " + secondsCoounter++);
                                System.out.println(fssSingleThreaded);
                                System.out.println(fssDoubleThreaded);
                                System.out.println(fssMultiThreaded);

                                do {
                                    Thread.sleep(1000);

                                    System.out.print(UP_AND_ERASE_LINE.repeat(lines));

                                    if(ERRORS) {
                                        printAndClear(fssSingleThreaded.errors);
                                        printAndClear(fssDoubleThreaded.errors);
                                        printAndClear(fssMultiThreaded.errors);
                                    }

                                    System.out.println("Seconds Passed: " + secondsCoounter++);
                                    System.out.println(fssSingleThreaded);
                                    System.out.println(fssDoubleThreaded);
                                    System.out.println(fssMultiThreaded);

                                } while (!Thread.interrupted()
                                        && (!fssSingleThreaded.isFinished
                                                || !fssDoubleThreaded.isFinished
                                                || !fssMultiThreaded.isFinished));

                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        })
                .start();

        String path = args.length == 1 ? args[0] : ".";
        System.out.println("Path: " + path);

        var t1 = new Thread(() -> singleThreadedCrawl(fssSingleThreaded, path));
        t1.start();
        t1.join();
        var t2 = new Thread(() -> doubleThreadedCrawl(fssDoubleThreaded, path));
        t2.start();
        t2.join();
        var t3 = new Thread(() -> multiThreadedCrawl(fssMultiThreaded, path));
        t3.start();
        t3.join();
    }

    private static void printAndClear(List<String> warnings) {

        List<String> wars;
        synchronized (warnings) {
            wars = new LinkedList<String>(warnings);
            warnings.clear();
        }

        wars.forEach(System.out::println);
    }

    public static void singleThreadedCrawl(FileSystemStatistics fss, String path) {
        requireNonNull(fss);
        requireNonNull(path);

        var file = new File(path);
        var stack = new LinkedList<File>();

        var start = System.currentTimeMillis();
        fss.startTimeMilli = start;
        try {

            while (file != null) {
                try {
                    if (!file.exists()) {
                        file = stack.pollFirst();
                        continue;
                    }

                    populate(fss, file);

                    if (file.isDirectory()
                            && file.canRead()
                            && !Files.isSymbolicLink(file.toPath())) {
                        stack.addAll(0, Arrays.asList(file.listFiles()));
                    }
                } catch (Exception e) {

                }

                file = stack.isEmpty() ? null : stack.removeFirst();
            }

        } finally {

            long endTime = System.currentTimeMillis();
            fss.timeTakenSec = (endTime - start) / 1000f;
            fss.endTimeMilli = endTime;
            fss.isFinished = true;
        }
    }

    public static void doubleThreadedCrawl(FileSystemStatistics fss, String path) {
        requireNonNull(fss);
        requireNonNull(path);

        final var pool = Executors.newFixedThreadPool(2);
        final var crawledFiles = new LinkedBlockingQueue<File>(10_000);
        final var isCrawlerFinished = new AtomicBoolean();

        fss.startTimeMilli = System.currentTimeMillis();

        var futureCrawler =
                pool.submit(new DoubleThreadedCrawler(path, crawledFiles, isCrawlerFinished, fss));

        var futureIndexer =
                pool.submit(
                        () -> {
                            try {
                                while ((!isCrawlerFinished.get() || !crawledFiles.isEmpty())
                                        && !Thread.interrupted()) {

                                    var file = crawledFiles.poll();
                                    if (file != null) {
                                        populate(fss, file);
                                    }
                                }
                            } catch (Exception e) {
                                fss.errors.add(e.toString());
                            }
                        });

        try {
            while (!futureCrawler.isDone() || !futureIndexer.isDone()) {
                Thread.sleep(500);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            pool.shutdownNow();
            long endTime = System.currentTimeMillis();
            fss.timeTakenSec = (endTime - fss.startTimeMilli) / 1000f;
            fss.endTimeMilli = endTime;
            fss.isFinished = true;
        }
    }

    private static final class DoubleThreadedCrawler implements Runnable {
        private final String path;
        private final int capacity = 10_000;
        private final FileSystemStatistics fss;
        private final LinkedBlockingQueue<File> crawledFiles;
        private final AtomicBoolean crawlerIsFinished;

        private DoubleThreadedCrawler(
                String path,
                LinkedBlockingQueue<File> crawledFiles,
                AtomicBoolean crawlerIsFinished,
                FileSystemStatistics fss) {
            this.path = path;
            this.fss = fss;
            this.crawledFiles = crawledFiles;
            this.crawlerIsFinished = crawlerIsFinished;
        }

        class DirStreamAndIter {
            final DirectoryStream<Path> stream;
            final Iterator<Path> iter;

            public DirStreamAndIter(DirectoryStream<Path> path, Iterator<Path> iter) {
                this.stream = path;
                this.iter = iter;
            }
        }

        @Override
        public void run() {

            File file = null;
            DirStreamAndIter dirStreamTuple = null;

            var fileQueue = new LinkedList<File>();
            var dirQueue = new LinkedList<DirStreamAndIter>();
            fileQueue.add(new File(path));

            try {
                while (((file = fileQueue.poll()) != null
                                || (dirStreamTuple = dirQueue.poll()) != null)
                        && !Thread.currentThread().isInterrupted()) {

                    // add the file for indexer, skip non existen file, if this is a
                    // directory that can be read, retrieve iterator, otherwise
                    // continue
                    if (file != null) {
                        if (!file.exists()) {
                            continue;
                        }

                        crawledFiles.put(file);

                        if (!file.isDirectory()
                                || !file.canRead()
                                || Files.isSymbolicLink(file.toPath())) {
                            continue;
                        }

                        var dirStream = Files.newDirectoryStream(file.toPath());
                        dirStreamTuple = new DirStreamAndIter(dirStream, dirStream.iterator());
                    }

                    // add files to queue from the directory untill capacity isn't
                    // full
                    while (dirStreamTuple.iter.hasNext() && fileQueue.size() < capacity) {
                        fileQueue.add(dirStreamTuple.iter.next().toFile());
                    }

                    if (dirStreamTuple.iter.hasNext()) {
                        dirQueue.add(dirStreamTuple);
                    } else {
                        dirStreamTuple.stream.close();
                    }
                }
            } catch (Throwable e) {
                fss.errors.add(Arrays.toString(e.getStackTrace()));
            } finally {
                dirQueue.forEach(
                        a -> {
                            try {
                                a.stream.close();
                            } catch (IOException e) {
                            }
                        });

                crawlerIsFinished.set(true);
            }
        }
    }

    public static void multiThreadedCrawl(FileSystemStatistics fss, String path) {
        requireNonNull(fss);
        requireNonNull(path);

        fss.startTimeMilli = System.currentTimeMillis();

        var poolSize = 10;
        var crawlerPool =
                new ThreadPoolExecutor(
                        poolSize,
                        poolSize,
                        0,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(poolSize),
                        Executors.defaultThreadFactory(),
                        new ThreadPoolExecutor.DiscardPolicy());

        var indexerPool =
                new ThreadPoolExecutor(
                        poolSize,
                        poolSize,
                        0,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<Runnable>(poolSize),
                        Executors.defaultThreadFactory(),
                        new ThreadPoolExecutor.DiscardPolicy());

        var crawledFilesQueue = new ArrayBlockingQueue<File>(10_000);
        var isCrawlerFinished = new AtomicBoolean();
        var isIndexerFinished = new AtomicBoolean();

        crawlerPool.submit(
                new MultithreadedFileCrawler(
                        path, crawledFilesQueue, crawlerPool, fss, isCrawlerFinished));

        indexerPool.submit(
                new MultithreadedFileIndexer(
                        crawledFilesQueue, indexerPool, fss, isCrawlerFinished, isIndexerFinished));

        try {
            while (!isCrawlerFinished.get() || !isIndexerFinished.get()) {
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            fss.errors.add(e.toString());
        } finally {
            crawlerPool.shutdown();
            indexerPool.shutdown();

            long endTime = System.currentTimeMillis();
            fss.timeTakenSec = (endTime - fss.startTimeMilli) / 1000f;
            fss.endTimeMilli = endTime;
            fss.isFinished = true;
        }
    }

    private static class MultithreadedFileCrawler implements Runnable {
        private final BlockingQueue<File> filesToCrawleQueue;
        private final BlockingQueue<File> crawledFilesQueue;
        private final ThreadPoolExecutor crawlerPool;
        private final BlockingQueue<DirStreamAndIter> dirsToCrawleQueue;
        private final int capacity = 10_000;
        private final FileSystemStatistics fss;
        private final AtomicBoolean isCrawlerFinished;
        private final AtomicInteger crawlerCounter;

        public MultithreadedFileCrawler(
                String path,
                BlockingQueue<File> crawledFilesQueue,
                ThreadPoolExecutor crawlerPoll,
                FileSystemStatistics fss,
                AtomicBoolean isCrawlerFinished) {

            this.crawledFilesQueue = requireNonNull(crawledFilesQueue);
            this.filesToCrawleQueue = new LinkedBlockingQueue<>(capacity);
            filesToCrawleQueue.add(new File(requireNonNull(path)));
            this.dirsToCrawleQueue = new LinkedBlockingQueue<>(capacity);
            this.crawlerPool = requireNonNull(crawlerPoll);
            this.fss = requireNonNull(fss);
            this.isCrawlerFinished = requireNonNull(isCrawlerFinished);
            this.crawlerCounter = new AtomicInteger();
        }

        private MultithreadedFileCrawler(
                BlockingQueue<File> crawledFilesQueue,
                BlockingQueue<File> filesToCrawleQueue,
                BlockingQueue<DirStreamAndIter> dirsToCrawleQueue,
                ThreadPoolExecutor crawlerPool,
                FileSystemStatistics fss,
                AtomicBoolean isCrawlerFinished,
                AtomicInteger crawlerCounter) {

            this.crawledFilesQueue = requireNonNull(crawledFilesQueue);
            this.filesToCrawleQueue = requireNonNull(filesToCrawleQueue);
            this.dirsToCrawleQueue = requireNonNull(dirsToCrawleQueue);
            this.crawlerPool = requireNonNull(crawlerPool);
            this.fss = requireNonNull(fss);
            this.isCrawlerFinished = requireNonNull(isCrawlerFinished);
            this.crawlerCounter = requireNonNull(crawlerCounter);
        }

        class DirStreamAndIter {
            final DirectoryStream<Path> stream;
            final Iterator<Path> iter;

            public DirStreamAndIter(DirectoryStream<Path> path, Iterator<Path> iter) {
                this.stream = path;
                this.iter = iter;
            }
        }

        public void run() {
            crawlerCounter.incrementAndGet();

            try {
                File file = null;
                DirStreamAndIter dirStreamTuple = null;

                while (((file = filesToCrawleQueue.poll()) != null
                                || (dirStreamTuple = dirsToCrawleQueue.poll()) != null)
                        && !Thread.interrupted()) {

                    if (file != null) {
                        if (!file.exists()) {
                            continue;
                        }

                        crawledFilesQueue.put(file);

                        if (!file.isDirectory()
                                || !file.canRead()
                                || Files.isSymbolicLink(file.toPath())) {
                            continue;
                        }

                        var dirStream = Files.newDirectoryStream(file.toPath());
                        dirStreamTuple = new DirStreamAndIter(dirStream, dirStream.iterator());
                    }

                    if (crawlerPool.getPoolSize() < crawlerPool.getMaximumPoolSize()) {
                        crawlerPool.execute(
                                new MultithreadedFileCrawler(
                                        crawledFilesQueue,
                                        filesToCrawleQueue,
                                        dirsToCrawleQueue,
                                        crawlerPool,
                                        fss,
                                        isCrawlerFinished,
                                        crawlerCounter));
                    }

                    // add files to queue from the directory untill capacity isn't
                    // full
                    while (dirStreamTuple.iter.hasNext() && filesToCrawleQueue.size() < capacity) {
                        filesToCrawleQueue.put(dirStreamTuple.iter.next().toFile());
                    }

                    if (dirStreamTuple.iter.hasNext()) {
                        dirsToCrawleQueue.add(dirStreamTuple);
                    } else {
                        dirStreamTuple.stream.close();
                    }
                }
            } catch (Exception e) {
                fss.errors.add(Arrays.toString(e.getStackTrace()));
            } finally {

                if (crawlerCounter.decrementAndGet() == 0) {
                    isCrawlerFinished.set(true);

                    dirsToCrawleQueue.forEach(
                            a -> {
                                try {
                                    a.stream.close();
                                } catch (IOException e) {
                                }
                            });
                }
            }
        }
    }

    private static final class MultithreadedFileIndexer implements Runnable {
        private final AtomicBoolean isCrawlerFinished;
        private final ArrayBlockingQueue<File> crawledFilesQueue;
        private final FileSystemStatistics fss;
        private final ThreadPoolExecutor indexerPool;
        private final AtomicBoolean isIndexerFinished;

        private MultithreadedFileIndexer(
                ArrayBlockingQueue<File> crawledFilesQueue,
                ThreadPoolExecutor indexerPool,
                FileSystemStatistics fss,
                AtomicBoolean crawlerIsFinished,
                AtomicBoolean indexerIsFinished) {

            this.crawledFilesQueue = crawledFilesQueue;
            this.indexerPool = indexerPool;
            this.fss = fss;
            this.isCrawlerFinished = crawlerIsFinished;
            this.isIndexerFinished = indexerIsFinished;
        }

        @Override
        public void run() {
            try {
                while ((!isCrawlerFinished.get() || !crawledFilesQueue.isEmpty())
                        && !Thread.interrupted()) {

                    var file = crawledFilesQueue.poll();
                    if (file != null) {
                        populate(fss, file);
                    }

                    if (indexerPool.getPoolSize() < indexerPool.getMaximumPoolSize()) {
                        indexerPool.submit(
                                new MultithreadedFileIndexer(
                                        crawledFilesQueue,
                                        indexerPool,
                                        fss,
                                        isCrawlerFinished,
                                        isIndexerFinished));
                    }
                }
            } catch (Exception e) {
                fss.errors.add(e.toString());
            } finally {
                isIndexerFinished.set(true);
            }
        }
    }

    private static void populate(FileSystemStatistics fss, File file) {
        requireNonNull(fss);
        requireNonNull(file);

        fss.totalFiles.getAndIncrement();

        if (file.isHidden()) {
            fss.hiddenFiles.getAndIncrement();
        }

        if (file.canExecute()) {
            fss.executableFiles.getAndIncrement();
        }

        if (file.canRead()) {
            fss.readableFiles.getAndIncrement();
        }

        if (file.canWrite()) {
            fss.writableFiles.getAndIncrement();
        }

        if (file.isFile()) {
            fss.numberOfFiles.getAndIncrement();
            fss.totalFilesSizeBytes.addAndGet(file.length());
            fss.maxFileSizeBytes.accumulateAndGet(file.length(), (a1, a2) -> Math.max(a1, a2));
            fss.maxFileSizeName.accumulateAndGet(
                    file.getName(),
                    (a1, a2) -> fss.maxFileSizeBytes.get() != file.length() ? a1 : a2);

        } else if (file.isDirectory()) {
            fss.numberOfDirectories.getAndIncrement();

            if (file.canRead()) {
                fss.maxFilesInDir.accumulateAndGet(
                        file.list().length, (a1, a2) -> Math.max(a1, a2));
            }
        }
    }
}
