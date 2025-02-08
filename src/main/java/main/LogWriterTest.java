package main;

import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

public class LogWriterTest {
    public static void main(String[] args)
            throws IOException, InterruptedException, ExecutionException {
        var ex = (ThreadPoolExecutor) Executors.newFixedThreadPool(4);

        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            var logWriter = new LogWriter(null);
            logWriter.start();
            ex.submit(logWriter::stop);
            ex.submit(
                    () -> {
                        try {
                            logWriter.log("");
                        } catch (Exception e) {
                        }
                    });

            logWriter.logThread.join();
            System.out.print("\rAttempt: " + i);

            if (!logWriter.queue.isEmpty()) {
                System.out.println("\nRegular LogWriter failed");
                break;
            }
        }

        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            var logWriter = new LogWriterWithLocks(null);
            logWriter.start();
            ex.submit(logWriter::stop);
            ex.submit(
                    () -> {
                        try {
                            logWriter.log("");
                        } catch (Exception e) {
                        }
                    });

            logWriter.logThread.join();
            System.out.print("\rAttempt: " + i);

            if (!logWriter.queue.isEmpty()) {
                System.out.println("\nLogWriter with locks failed");
                break;
            }
        }
    }

    static class LogWriter {
        private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(1000);
        private final Thread logThread;
        private volatile boolean isStopped;
        private Runnable target;

        public LogWriter(Writer writer) {
            target =
                    () -> {
                        try {
                            while (!isStopped) {
                                writer.write(queue.take());
                            }
                        } catch (IOException | InterruptedException e) {
                        } finally {
                            while (!queue.isEmpty()) {
                                try {
                                    queue.take();
                                } catch (Exception e) {
                                }
                            }
                        }
                    };
            this.logThread = new Thread(target);
        }

        public void start() {
            logThread.start();
        }

        public void stop() {
            isStopped = true;
            logThread.interrupt();
        }

        public void log(String msg) throws InterruptedException {
            if (!isStopped) {
                queue.put(msg);
            } else {
                throw new IllegalStateException();
            }
        }
    }

    static class LogWriterWithLocks {
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(1000);
        private final Thread logThread;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private final ReadLock readLock = lock.readLock();
        private final WriteLock writeLock = lock.writeLock();
        private volatile boolean isStopped;

        public LogWriterWithLocks(Writer writer) {
            this.logThread =
                    new Thread(
                            () -> {
                                try {
                                    while (true) {
                                        queue.take();
                                    }
                                } catch (InterruptedException e) {
                                } finally {
                                    writeLock.lock();
                                    try {
                                        while (!queue.isEmpty()) {
                                            queue.take();
                                        }
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    } finally {
                                        writeLock.unlock();
                                    }
                                }
                            });
        }

        public void start() {
            logThread.start();
        }

        public void stop() {
            isStopped = true;
            logThread.interrupt();
        }

        public void log(String msg) throws InterruptedException {
            readLock.lock();
            if (!isStopped) {
                queue.put(msg);
            } else {
                readLock.unlock();
                throw new IllegalStateException();
            }
            readLock.unlock();
        }
    }
}
