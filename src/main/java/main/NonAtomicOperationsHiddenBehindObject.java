package main;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class NonAtomicOperationsHiddenBehindObject {

    public static void main(String[] args) {
        var ex = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        test(ex, new NotSafeFactorization());
        test(ex, new ThreadSafeFactorization());

        ex.shutdownNow();
    }

    private static void test(ExecutorService ex, Factorizer factorizer) {
        var counter = 0;
        var throwable = new AtomicReference<Throwable>();

        while (true) {
            ex.submit(
                    () -> {
                        try {
                            factorizer.factorize((int) (Math.random() * 1000));
                        } catch (Throwable e) {
                            throwable.set(e);
                        }
                    });
            if (throwable.get() != null) {
                System.out.println(throwable.get());
                break;
            }

            counter++;
        }

        System.out.println("%s Attempts: %s".formatted(factorizer.getClass(), counter));
    }

    private interface Factorizer {
        int factorize (int i);
    }
    private static class NotSafeFactorization implements Factorizer {
        private final AtomicInteger i = new AtomicInteger();
        private final AtomicInteger r = new AtomicInteger();

        public  int factorize(int i) {
            if (this.i.get() != i) {
                    r.set(factor(i));
                    this.i.set(i);
            }
            int j = r.get();

            assert j == factor(i)
                    : "Ooops! Result: %s should be equal to %s for i: %s%n"
                            .formatted(j, factor(i), i);

            return j;
        }

        private static int factor(int i) {
            return i % 5;
        }
    }

    private static class ThreadSafeFactorization implements Factorizer {
        private volatile OneTimeCache cache = null;

        private static class OneTimeCache {
            private final int i;
            private final int f;

            public OneTimeCache(int i, int f) {
                this.i =  i;
                this.f =  f;
            }
            public Optional<Integer> getFactor(int i){
                return this.i == i ? Optional.of(f) : Optional.empty(); 
            }
        }


        public int factorize(int i) {
            Optional<Integer> optFactor = cache == null ?  Optional.empty() : cache.getFactor(i);

            if (optFactor.isEmpty()) {
                    int factor = factor(i);
                    cache = new OneTimeCache(i, factor);
                    return factor;
            }
            int j = optFactor.get();

            assert j == factor(i)
                    : "Ooops! Result: %s should be equal to %s for i: %s%n"
                            .formatted(j, factor(i), i);

            return j;
        }

        private static int factor(int i) {
            return i % 5;
        }
    }
}
