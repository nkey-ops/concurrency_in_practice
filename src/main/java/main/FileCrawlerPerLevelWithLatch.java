package main;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class FileCrawlerPerLevelWithLatch {

    public static void main(String[] args) {
        var path = args.length == 1 ? args[0] : ".";
        crawl(new File(path));
    }

    /**
     * Crawl from the root down to all possible directories on per level bases an gather information
     * for each level of directories. Threads will parse only one level at a time, counting down a
     * latch to signal that each threads finished working. After latch has reached zero, information
     * gathered about the current level will be printed to the console and now a next level will be
     * parsed in the same way until there are no left.
     *
     * <p>Two queries will be used to store files on the current level and the next level down.
     *
     * <p>A count down latch will be created with value equal to the number of threads created for
     * the current level.
     *
     * <p>Each thread will pull a file or directory from the current level queue and store
     * information about the file or if it is a directory goes through all of its files and
     * directories on first level and adds them to the next level queue.
     *
     * <p>After a thread parses a file or directory, it will repeate the operation again until
     * current level queue is exhaused. After the queue is exhausted the thread will perform a count
     * down on the latch and finish the task. When the latch reaches zero, information about the
     * current level will be outputted to the console.
     *
     * <p>Next level queue now becomes a current level queue, a new latch is created and the cycle
     * is repeated till the next level queue is empty.
     */
    public static void crawl(File root) {
        var corePoolSize = Runtime.getRuntime().availableProcessors();
        var pool =
                new ThreadPoolExecutor(
                        corePoolSize,
                        corePoolSize,
                        0,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(corePoolSize));
        var currentLevelQueue = new LinkedBlockingQueue<File>();
        var nextLevelQueue = new LinkedBlockingQueue<File>();

        currentLevelQueue.add(requireNonNull(root));

        long allFiles = 0;
        long startTime = System.currentTimeMillis();
        try {

            int levelCounter = 0;
            while (currentLevelQueue.size() > 0) {
                var levelStatistics = new LevelStatistics(++levelCounter, currentLevelQueue.size());

                // creating one thread per 100 files up to max capacity of the thread poll
                var threadsToCreate = Math.max(currentLevelQueue.size() / 100, 1);

                if (threadsToCreate > pool.getMaximumPoolSize()) {
                    threadsToCreate = pool.getMaximumPoolSize();
                }

                assert threadsToCreate > 0 : "threadsToCreate should be bigger than 0";
                assert threadsToCreate <= pool.getMaximumPoolSize()
                        : "threadsToCreate should be less or equal to max pool size";

                var countDownLatch = new CountDownLatch(threadsToCreate);
                for (int i = 0; i < threadsToCreate; i++) {
                    pool.execute(
                            new FileCrawler(
                                    currentLevelQueue,
                                    nextLevelQueue,
                                    levelStatistics,
                                    countDownLatch));
                }

                countDownLatch.await();

                System.out.println(levelStatistics);
                allFiles += levelStatistics.totalFilesAndDirectories;
                assert currentLevelQueue.isEmpty() : "Current level queue should be empty";

                var tmp = currentLevelQueue;
                currentLevelQueue = nextLevelQueue;
                nextLevelQueue = tmp;
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.out.printf("Total Files: %s | Time Taken: %.2f sec%n",allFiles, (System.currentTimeMillis() - startTime) / 1000f);
            pool.shutdownNow();
        }
    }

    private static class FileCrawler implements Runnable {

        private BlockingQueue<File> currentLevel;
        private CountDownLatch countDownLatch;
        private LinkedBlockingQueue<File> nextLevelQueue;
        private LevelStatistics levelStatistics;

        public FileCrawler(
                BlockingQueue<File> currentLevel,
                LinkedBlockingQueue<File> nextLevelQueue,
                LevelStatistics levelStatistics,
                CountDownLatch countDownLatch) {

            this.nextLevelQueue = requireNonNull(nextLevelQueue);
            this.currentLevel = requireNonNull(currentLevel);
            this.levelStatistics = requireNonNull(levelStatistics);
            this.countDownLatch = requireNonNull(countDownLatch);
        }

        @Override
        public void run() {
            try {
                for (var file = currentLevel.poll();
                        file != null && !Thread.currentThread().isInterrupted();
                        file = currentLevel.poll()) {

                    if (!file.exists() || !file.canRead() || Files.isSymbolicLink(file.toPath())) {
                        if (!file.exists()) {
                            levelStatistics.nonExistentFiles.incrementAndGet();
                        } else if (!file.canRead()) {
                            levelStatistics.unreadable.incrementAndGet();
                        } else {
                            levelStatistics.symbolicReferences.incrementAndGet();
                        }
                        continue;
                    }

                    if (file.isDirectory()) {
                        var list = file.listFiles();
                        if (list != null) {
                            for (File subFile : list) {
                                nextLevelQueue.add(subFile);
                            }
                        }
                        levelStatistics.numberOfDirectories.incrementAndGet();
                    } else {
                        levelStatistics.numberOfFiles.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                countDownLatch.countDown();
            }
        }
    }

    private static class LevelStatistics {
        public final AtomicInteger unreadable = new AtomicInteger();
        public final AtomicInteger nonExistentFiles = new AtomicInteger();
        public final AtomicInteger numberOfFiles = new AtomicInteger();
        public final AtomicInteger numberOfDirectories = new AtomicInteger();
        public final AtomicInteger symbolicReferences = new AtomicInteger();
        public final int totalFilesAndDirectories;
        public final int levelCounter;

        public LevelStatistics(int levelCounter, int totalFilesAndDirs) {
            this.levelCounter = levelCounter;
            this.totalFilesAndDirectories = totalFilesAndDirs;
        }

        @Override
        public String toString() {
            return """
level: %5s | files:  %7s | dirs: %7s | total: %7s | symb-ref: %7s | non-exist: %7s | unread: %7s\
"""
                    .formatted(
                            levelCounter,
                            numberOfFiles.get(),
                            numberOfDirectories.get(),
                            totalFilesAndDirectories,
                            symbolicReferences.get(),
                            nonExistentFiles.get(),
                            unreadable.get());
        }
    }
}
