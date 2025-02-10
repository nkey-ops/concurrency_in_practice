package main;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy;
import java.util.concurrent.ThreadPoolExecutor.DiscardPolicy;
import java.util.concurrent.TimeUnit;

public class RejectExecutionPolicies {
    public static void main(String[] args) throws InterruptedException, ExecutionException {
        testAbortPolicy();
        testDiscardPolicy();
        testDiscardOldestPolicy();
        testCallerRunsPolicy();
    }

    private static void testAbortPolicy() throws InterruptedException {
        System.out.println("Test Abort Policy");
        var p =
                new ThreadPoolExecutor(
                        1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1), new AbortPolicy());

        for (int i = 1; i <= 4; i++) {
            int n = i;
            try {
                p.execute(
                        () -> {
                            try {
                                System.out.println("Executed: #" + n);
                                while (true) {
                                    Thread.sleep(1000);
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
            } catch (RejectedExecutionException e) {
                System.out.println("Rejected: #" + i);
            }
        }

        Thread.sleep(5);
        assert p.getQueue().size() == 1;
        p.shutdownNow();
    }

    private static void testDiscardPolicy() throws InterruptedException {
        System.out.println("Test Discard Policy");
        var p =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(1),
                        new DiscardPolicy());

        for (int i = 1; i <= 4; i++) {
            int n = i;
            try {
                p.execute(
                        () -> {
                            try {
                                System.out.println("Executed: #" + n);
                                while (true) {
                                    Thread.sleep(1000);
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
            } catch (RejectedExecutionException e) {
                assert false : "Shouldn't be rejected";
            }
        }

        Thread.sleep(5);
        assert p.getQueue().size() == 1;
        p.shutdownNow();
    }

    private static void testDiscardOldestPolicy() throws InterruptedException {
        System.out.println("Test Discard Oldest Policy");
        var p =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(1),
                        new DiscardOldestPolicy());

        Future<?> firstTask = null;
        for (int i = 1; i <= 4; i++) {
            int n = i;
            try {
                var f =
                        p.submit(
                                () -> {
                                    try {
                                        System.out.println("Executed: #" + n);
                                        while (true) {
                                            Thread.sleep(1000);
                                        }
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
                if (i == 1) firstTask = f;
            } catch (RejectedExecutionException e) {
                assert false : "Shouldn't be rejected";
            }
        }

        Thread.sleep(2);
        assert p.getQueue().size() == 1;
        firstTask.cancel(true);
        Thread.sleep(2);
        assert p.getQueue().size() == 0;
        p.shutdownNow();
    }

    private static void testCallerRunsPolicy() throws InterruptedException, ExecutionException {
        System.out.println("Test Caller Runs Policy");
        var p =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(1),
                        new CallerRunsPolicy());
        var f1 =
                p.submit(
                        () -> {
                            try {
                                System.out.println("Executed: # 1");
                                while (true) {
                                    Thread.sleep(1000);
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
        var f2 =
                p.submit(
                        () -> {
                                System.out.println("Executed: # 2");
                        });

        for (int i = 3; i <= 4; i++) {
            int n = i;
            try {
                System.out.println("Creating Task #" + n);
                p.submit(
                        () -> {
                            System.out.println("Executed: #" + n);
                        });
            } catch (RejectedExecutionException e) {
                assert false : "Shouldn't be rejected";
            }
        }

        f1.cancel(true);
        f2.get();

        p.shutdownNow();
    }
}
