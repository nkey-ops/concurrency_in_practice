package main;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;

public class MemoizerComparison {

    public interface Computable<A, R> {
        R compute(A argument) throws InterruptedException;
    }

    public static class ExpensiveFunction implements Computable<String, BigInteger> {
        public BigInteger compute(String arg) throws InterruptedException {
            Thread.sleep(500);
            return new BigInteger(arg);
        }
    }

    public static class Memoizer1<A, V> implements Computable<A, V> {
        private final Map<A, V> cache = new HashMap<A, V>();

        private final Computable<A, V> c;

        public Memoizer1(Computable<A, V> c) {
            this.c = c;
        }

        public synchronized V compute(A arg) throws InterruptedException {
            V result = cache.get(arg);
            if (result == null) {
                result = c.compute(arg);
                cache.put(arg, result);
            }
            return result;
        }
    }

    public static class Memoizer2<A, V> implements Computable<A, V> {
        private final Map<A, V> cache = new ConcurrentHashMap<A, V>();

        private final Computable<A, V> c;

        public Memoizer2(Computable<A, V> c) {
            this.c = c;
        }

        public V compute(A arg) throws InterruptedException {
            V result = cache.get(arg);
            if (result == null) {
                result = c.compute(arg);
                cache.put(arg, result);
            }
            return result;
        }
    }

    public static class Memoizer3<A, V> implements Computable<A, V> {
        private final Map<A, FutureTask<V>> cache = new ConcurrentHashMap<>();

        private final Computable<A, V> c;

        public Memoizer3(Computable<A, V> c) {
            this.c = c;
        }

        public V compute(A arg) throws InterruptedException {
            try {
                var result = cache.get(arg);
                if (result == null) {
                    var ft = new FutureTask<V>(() -> c.compute(arg));
                    cache.put(arg, ft);
                    ft.run();
                    return ft.get();
                } else {
                    return result.get();
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        int nThreads = 10000;
        var ex = (ThreadPoolExecutor) Executors.newFixedThreadPool(nThreads);

        System.out.println("Started");

        var mem1 =
                new FutureTask<Long>(
                        () ->
                                test(
                                        nThreads,
                                        ex,
                                        new Memoizer1<String, BigInteger>(
                                                new ExpensiveFunction())));
        var mem2 =
                new FutureTask<Long>(
                        () ->
                                test(
                                        nThreads,
                                        ex,
                                        new Memoizer2<String, BigInteger>(
                                                new ExpensiveFunction())));
        var mem3 =
                new FutureTask<Long>(
                        () ->
                                test(
                                        nThreads,
                                        ex,
                                        new Memoizer3<String, BigInteger>(
                                                new ExpensiveFunction())));


        var test2 =
                new FutureTask<Long>(
                        () ->
                                test(
                                        nThreads,
                                        ex,
                                        new Memoizer2<String, BigInteger>(
                                                new ExpensiveFunction())));
        var test3 =
                new FutureTask<Long>(
                        () ->
                                test(
                                        nThreads,
                                        ex,
                                        new Memoizer3<String, BigInteger>(
                                                new ExpensiveFunction())));


        var seconds = 1;

        // seconds = wait(mem1, seconds, 1);
        seconds = wait(test3, seconds, -1);
        seconds = wait(test2, seconds, -1);
        seconds = wait(mem3, seconds, 3);
        seconds = wait(mem2, seconds, 2);

        ex.shutdownNow();
    } 

    private static int wait(FutureTask<Long> mem1, int seconds, int i)
            throws InterruptedException, ExecutionException {
        new Thread(() -> mem1.run()).start();
        while (!mem1.isDone()) {
            Thread.sleep(1000);
            System.out.print("\rSeconds: " + seconds++);
        }

        System.out.println("\rMem %s Time: %.5f sec".formatted(i, mem1.get() / 1000f));
        System.out.print("\rSeconds: " + seconds++);
        return seconds;
    }

    private static long test(
            int nThreads, ThreadPoolExecutor ex, Computable<String, BigInteger> mem1) {
        var start = System.currentTimeMillis();
        for (int i = 0; i < nThreads / 2; i++) {
            var i2 = i;
            ex.submit(() -> mem1.compute(String.valueOf(i2)));
        }
        for (int i = 0; i < nThreads / 2; i++) {
            var i2 = i;
            ex.submit(() -> mem1.compute(String.valueOf(i2)));
        }

        while (ex.getActiveCount() != 0) {
            Thread.onSpinWait();
        }
        return System.currentTimeMillis() - start;
    }
}
