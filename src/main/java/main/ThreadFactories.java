package main;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadFactories {

    public static void main(String[] args) {
        var ex = new ThreadPoolExecutor(0,100, 1, TimeUnit.SECONDS, new SynchronousQueue<>(), new LoggingThreadFactory()) ;
        

        for (int i = 0; i < 10; i++) {
            var n = i;
            ex.submit(
                    () -> {
                        try {
                            System.out.println("Starting task #" + n);
                                Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            Thread.currentThread().interrupt();
                        }finally {
                            System.out.println("Ending task #" + n);
                        }
                    });
        }
    }

    private static class LoggingThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable r) {
            return new LoggingThread(r, "LogginThreadFactory Thread");
        }
    }

    private static class LoggingThread extends Thread {
        public static final AtomicInteger createThreads = new AtomicInteger();
        public static final AtomicInteger activeThreads = new AtomicInteger();

        public LoggingThread(Runnable r, String name) {
            super(r, name + " #" + createThreads.incrementAndGet());

            setUncaughtExceptionHandler(
                    (t, th) -> {
                        System.out.println("%s | Uncaught Exception: %s".formatted(this, th));
                    });
        }

        @Override
        public void run() {
            System.out.println("%s | Started Thread".formatted(this));
            try {
                activeThreads.incrementAndGet();
                super.run();
            } finally {
                activeThreads.decrementAndGet();
                System.out.println("%s | Finished Thread".formatted(this));
            }
        }
    }
}
