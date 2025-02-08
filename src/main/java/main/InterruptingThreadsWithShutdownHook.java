package main;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class InterruptingThreadsWithShutdownHook {
    public static void main(String[] args) throws InterruptedException {
        new LogService().start();
    }

    private static class LogService {
        private volatile State state = State.NEW;
        private final ExecutorService ex = Executors.newSingleThreadExecutor();

        private static enum State {
            NEW,
            RUNNING,
            STOPPED
        }

        public synchronized void start() {
            switch (state) {
                case NEW -> {
                    state = State.RUNNING;
                    ex.submit(this::run);
                    Runtime.getRuntime()
                            .addShutdownHook(
                                    new Thread(
                                            () -> {
                                                try {
                                                    this.stop();
                                                } catch (InterruptedException e) {
                                                    e.printStackTrace();
                                                }
                                            }));
                }
                case RUNNING, STOPPED ->
                        throw new IllegalStateException("Task is already: " + state);
            }
        }

        public synchronized void stop() throws InterruptedException {
            switch (state) {
                case NEW -> {
                    state = State.STOPPED;
                }
                case RUNNING -> {
                    state = State.STOPPED;
                    System.out.println("Attempting to shutdown");
                    ex.shutdown();
                    if (!ex.awaitTermination(2, TimeUnit.SECONDS)) {
                        System.out.println("Didn't shutdown at first");
                        ex.shutdownNow();
                        if (!ex.awaitTermination(10, TimeUnit.SECONDS)) {
                            System.err.println("Couldn't terminate");
                        }
                    }
                }
                case STOPPED -> {}
            }
        }

        private void run() {
            var isInterrupted = false;
            try {
                while (state == State.RUNNING) {
                    System.out.println("Logging task");
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                isInterrupted = true;
                System.out.println("Logger was interrupted");
            } finally {
                System.out.println("Stopping Logger");
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    isInterrupted = true;
                    System.out.println("Logger was interrupted in finally");
                }
                System.out.println("Logger was stopped");

                state = State.STOPPED;

                if (isInterrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
