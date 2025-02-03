package main;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
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
            Thread.sleep(50);
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

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        int nThreads = 10;
        var ex =(ThreadPoolExecutor) Executors.newFixedThreadPool(nThreads); 

        var mem1 = new Memoizer1<String, BigInteger>(new ExpensiveFunction());

        System.out.println("Started");
        var timeSpent = new FutureTask<Long>(() -> test(nThreads, ex, mem1));
        new Thread(() -> timeSpent.run()).start();

        var seconds = 1;
        System.out.print(seconds);
        while(!timeSpent.isDone()){
            Thread.sleep(1000);
            System.out.print("\r" + seconds++);
        }
        System.out.println("%nMem1 Time: %.3f sec".formatted(timeSpent.get() / 1000f ));

        ex.shutdownNow();
    }

    private static long test(int nThreads, ThreadPoolExecutor ex, Memoizer1<String, BigInteger> mem1) {
        var start = System.currentTimeMillis();
        for (int i = 0; i < nThreads; i++) {
            var i2 = i;
            ex.submit(() -> mem1.compute(String.valueOf(i2))); 
        }

        while(ex.getActiveCount() != 0) {
            Thread.onSpinWait();
        }
        return System.currentTimeMillis() - start;
    }
}
