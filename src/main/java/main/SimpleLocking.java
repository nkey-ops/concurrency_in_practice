package main;

import java.lang.management.ManagementFactory;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class SimpleLocking {

    private interface Account {
        DollarAmount getBalance();

        void debit(DollarAmount amount);

        void credit(DollarAmount amount);
    }

    private interface DollarAmount extends Comparable<DollarAmount> {}

    private static class InsufficientFundsException extends RuntimeException {}

    public void transferMoneyUnsafe(Account fromAccount, Account toAccount, DollarAmount amount)
            throws InsufficientFundsException {
        synchronized (fromAccount) {
            synchronized (toAccount) {
                if (fromAccount.getBalance().compareTo(amount) < 0)
                    throw new InsufficientFundsException();
                else {
                    fromAccount.debit(amount);
                    toAccount.credit(amount);
                }
            }
        }
    }

    private static final Object tieLock = new Object();

    public void transferMoneySafe(
            final Account fromAcct, final Account toAcct, final DollarAmount amount)
            throws InsufficientFundsException {

        class Helper {
            public void transfer() throws InsufficientFundsException {
                if (fromAcct.getBalance().compareTo(amount) < 0)
                    throw new InsufficientFundsException();
                else {
                    fromAcct.debit(amount);
                    toAcct.credit(amount);
                }
            }
        }
        int fromHash = System.identityHashCode(fromAcct);
        int toHash = System.identityHashCode(toAcct);
        if (fromHash < toHash) {
            synchronized (fromAcct) {
                synchronized (toAcct) {
                    new Helper().transfer();
                }
            }
        } else if (fromHash > toHash) {
            synchronized (toAcct) {
                synchronized (fromAcct) {
                    new Helper().transfer();
                }
            }
        } else {
            synchronized (tieLock) {
                synchronized (fromAcct) {
                    synchronized (toAcct) {
                        new Helper().transfer();
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        testTransferUnsafeForDeadlocks();
    }

    private static void testTransferUnsafeForDeadlocks() throws InterruptedException {
        class MockDollarAmount implements DollarAmount {
            @Override
            public int compareTo(DollarAmount o) {
                return 0;
            }
        }

        class MockAccount implements Account {

            private final DollarAmount dm;

            public MockAccount(DollarAmount dm) {
                this.dm = dm;
            }

            @Override
            public DollarAmount getBalance() {
                return dm;
            }

            @Override
            public void debit(DollarAmount amount) {}

            @Override
            public void credit(DollarAmount amount) {}
        }

        var mockAmount = new MockDollarAmount();
        var accounts =
                List.of(
                        new MockAccount(mockAmount),
                        new MockAccount(mockAmount),
                        new MockAccount(mockAmount),
                        new MockAccount(mockAmount));

        var simpleLocking = new SimpleLocking();

        var thf =
                new ThreadFactory() {
                    final Queue<Thread> createdThreads = new ConcurrentLinkedQueue<>();

                    @Override
                    public Thread newThread(Runnable r) {
                        var thread = new Thread(r);
                        createdThreads.add(thread);
                        return thread;
                    }
                };

        int capacity = 1_000_000_000;
        var ex =
                new ThreadPoolExecutor(
                        10, 10, 50, TimeUnit.SECONDS, new LinkedBlockingQueue<>(capacity), thf);
        ex.prestartCoreThread();

        var random = new Random();
        Runnable task =
                () -> {
                    // replace with simpleLocking.transferMoneySafe(fromAccount, toAccount, amount);
                    simpleLocking.transferMoneyUnsafe(
                            accounts.get(random.nextInt(accounts.size())),
                            accounts.get(random.nextInt(accounts.size())),
                            mockAmount);
                };

        var startTimeMillis = System.currentTimeMillis();
        var timeout = 200;

        try {
            do {
                ex.submit(task);
            } while (System.currentTimeMillis() - startTimeMillis < timeout);
        } catch (Exception e) {
        }

        System.out.println(ex.getCompletedTaskCount());
        ex.shutdown();
        ex.awaitTermination(200, TimeUnit.MILLISECONDS);
        ex.shutdownNow();
        System.out.println(ex.getCompletedTaskCount());

        System.out.println("Detecting DeadLock");
        var wasDeadlocked = detectDeadLock(thf.createdThreads);
        System.out.println("DeadLock was " + (wasDeadlocked ? "FOUND!!" : "not found"));
    }

    private static boolean detectDeadLock(Iterable<Thread> threads) {
        var blockedThreads = new LinkedList<Thread>();
        threads.forEach(
                t -> {
                    if (t.getState() == Thread.State.BLOCKED) {
                        blockedThreads.add(t);
                    }
                    System.out.printf(
                            "id:%s | name:%s | state:%s%n", t.getId(), t.getName(), t.getState());
                });

        var threadMXBean = ManagementFactory.getThreadMXBean();

        for (var blockedThread : blockedThreads) {
            for (var potentialOwner : blockedThreads) {
                if (blockedThread.getId() == potentialOwner.getId()) {
                    continue;
                }
                if (threadMXBean.getThreadInfo(blockedThread.getId()).getLockOwnerId()
                        == potentialOwner.getId()) {
                    return true;
                }
            }
        }
        return false;
    }
}
