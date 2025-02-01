package main;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * You are tasked with building a multithreaded simulation of a 2D graphical "bouncing ball"
 * animation that involves high thread contention. The animation will be rendered on a text-based
 * grid in the console, and each ball will be represented by an ASCII character like O or *. The
 * main goal is to have multiple balls moving around the grid, bouncing off the walls, and being
 * synchronized across multiple threads using semaphores to control access to shared resources (such
 * as the console and the ball positions).
 *
 * <p>Requirements: Threads: The system will create multiple balls, each controlled by a separate
 * thread. Each ball moves independently but is synchronized via semaphores to avoid race conditions
 * when accessing shared resources (like the console).
 *
 * <p>Semaphore Usage: Use semaphores to manage:
 *
 * <p>Access to the console (so only one thread writes to it at a time). Movement of balls in the
 * grid (to prevent balls from interfering with each other’s positions). Thread synchronization
 * (controlling when threads should stop or wait). Graphical Output: The graphical output is a
 * console-based grid that simulates a bouncing effect for each ball. Every time a ball reaches a
 * wall, it should "bounce" off, and the new position should be displayed.
 *
 * <p>High Thread Contention: By having many balls on the grid and using semaphores to control
 * access to shared resources, the program will experience high thread contention, especially in the
 * critical section of updating the grid and rendering.
 *
 * <p>High-Level Steps: Create a Ball class: This class will manage the position, velocity, and
 * behavior of the ball. Create a BouncingBallSimulation class: This will handle the main simulation
 * loop, where the balls bounce on a grid. It will use semaphores for synchronization. Grid
 * Rendering: Write code to render the ball’s position in a text-based grid on the console.
 * Semaphore Operations: You will use Semaphore.acquire(), Semaphore.release(), and
 * Semaphore.availablePermits() to control thread access to shared resources (like the grid and ball
 * positions).
 *
 * <p>Solution:
 *
 * <p>A {@link Grid} is created with a box inside it. The balls' positions are randomly generated
 * as their directions. This box can only contain a limited number of balls. A {@link Semaphore} is
 * used to provide a permit to a ball to access the box, if the box is full, the permit isn't
 * provided which makes the ball bounce off the side.
 *
 * <p>There is a certain a number of times a ball should bounce off the sides of the box in order to
 * have an access to it besides a permit from the {@link Semaphore}. The same goes for when the ball
 * is inside the box, it also has to make a certain number of bounces in order to leave it and the
 * release the permit.
 */
public class BouncingBallWithSemaphore {

    private static class Ball {
        private int x;
        private int y;
        private int nx;
        private int ny;
        private int px;
        private int py;
        public final int xBoundary;
        public final int yBoundary;
        public char sign = '○';

        /**
         * @param x x >= 0 and x < xBoundary
         * @param y y >= 0 and y < yBondary
         * @param nx next directon of a ball [-1, 0, 1], nx + x >= 0 and nx + x < xBoundary
         * @param ny next directo of a ball [-1, 0, 1], ny + y >= 0 and ny +y < yBondary
         * @param xBoundary higher limit of x,
         * @param yBoundary higher limit of y
         */
        public Ball(int x, int y, int nx, int ny, int xBoundary, int yBoundary) {
            validate(x, y, nx, ny, xBoundary, yBoundary);

            this.x = x;
            this.y = y;
            this.px = x;
            this.py = y;
            this.nx = nx;
            this.ny = ny;
            this.xBoundary = xBoundary;
            this.yBoundary = yBoundary;
        }

        /**
         * @param x x >= 0 and x < xBoundary
         * @param y y >= 0 and y < yBondary
         * @param nx next directon of a ball [-1, 0, 1], nx + x >= 0 and nx + x < xBoundary
         * @param ny next directo of a ball [-1, 0, 1], ny + y >= 0 and ny +y < yBondary
         * @param xBoundary higher limit of x,
         * @param yBoundary higher limit of y
         */
        private void validate(int x, int y, int nx, int ny, int xBoundary, int yBoundary) {
            if (nx < -1 || nx > 1 || ny < -1 || ny > 1) {
                throw new IllegalArgumentException(
                        "nx %s or ny %s can have only values -1, 0, 1".formatted(nx, ny));
            }
            if (xBoundary <= 0 || yBoundary <= 0) {
                throw new IllegalArgumentException(
                        "xBoundary %s or yBoundary %s cannot be below or equal to 0");
            }

            if (x < 0 || nx + x < 0) {
                throw new IllegalArgumentException(
                        "x %s or nx %s and nx + x %s cannot be below 0".formatted(x, nx, nx + x));
            }

            if (y < 0 || ny + y < 0) {
                throw new IllegalArgumentException(
                        "y %s or ny %s and ny + y %s cannot be below 0".formatted(y, ny, ny + y));
            }

            if (x >= xBoundary || nx >= xBoundary) {
                throw new IllegalArgumentException(
                        "x %s or nx %s and nx + x %s cannot be more or equal to %s"
                                .formatted(x, nx, nx + x, xBoundary));
            }

            if (y >= yBoundary || ny + y >= yBoundary) {
                throw new IllegalArgumentException(
                        "y %s or ny %s and ny + y %s cannot be more or equal to %s"
                                .formatted(y, ny, y + ny, yBoundary));
            }
        }

        /**
         * @param x x >= 0 and x < xBoundary
         * @param y y >= 0 and y < yBondary
         * @param nx next directon of a ball [-1, 0, 1], nx + x >= 0 and nx + x < xBoundary
         * @param ny next directo of a ball [-1, 0, 1], ny + y >= 0 and ny +y < yBondary
         */
        public void set(int x, int y, int nx, int ny) {
            validate(x, y, nx, ny, xBoundary, yBoundary);

            this.px = this.x;
            this.py = this.y;
            this.x = x;
            this.y = y;
            this.nx = nx;
            this.ny = ny;
        }
    }

    private static class Grid {
        public final int height;
        public final int length;
        public final int boxHeigth;
        public final int boxLength;
        public final int boxTimesSmaller = 3;

        public Grid(int length, int height) {
            if (height < boxTimesSmaller || length < boxTimesSmaller) {
                throw new IllegalArgumentException(
                        "height %s or length %s cannot be below 3".formatted(height, length));
            }

            this.height = height;
            this.length = length;
            this.boxHeigth = height / boxTimesSmaller;
            this.boxLength = length / boxTimesSmaller;
        }

        public boolean isOnBoxBoundaryX(int x) {
            return (x >= boxLength && x < length - boxLength);
        }

        public boolean isOnBoxBoundaryX(int x, int y) {
            return (x == boxLength || x == length - (boxLength) - 1)
                    && (y >= boxHeigth && y < height - (boxHeigth));
        }

        public boolean isOnBoxBoundaryY(int x, int y) {
            return (y == boxHeigth || y == height - boxHeigth - 1)
                    && (x >= boxLength && x < length - boxLength);
        }
    }

    private static class BallRunnable implements Runnable {
        private final Ball ball;
        private final Grid grid;
        private final Semaphore semaphore;
        private boolean isInsideBox;
        private int countBouncesInsideAgainstBox;
        private int countBouncesOutsideAgainstBox;
        private final int bouncesInsideAgainstBox = 20;
        private final int bouncesOutsideAgainstBox = 5;

        public BallRunnable(Grid grid, Semaphore sempahore) {
            var random = new Random();

            int x =
                    random.nextInt(2) == 0
                            ? random.nextInt(1, grid.boxLength)
                            : random.nextInt(grid.length - grid.boxLength, grid.length - 1);

            int y =
                    random.nextInt(2) == 0
                            ? random.nextInt(1, grid.boxHeigth)
                            : random.nextInt(grid.height - grid.boxHeigth, grid.height - 1);

            int nx = random.nextInt(-1, 2);
            int ny = nx != 0 ? random.nextInt(-1, 2) : random.nextInt(2) == 0 ? -1 : 1;

            assert ny >= -1 && ny <= 1;
            assert nx >= -1 && nx <= 1;
            assert nx != 0 || ny != 0;
            // outside the box
            assert (x > 0 && x < grid.boxLength)
                    || (x >= grid.length - grid.boxLength && x < grid.length);
            assert (y > 0 && y < grid.boxHeigth)
                    || (y >= grid.height - grid.boxHeigth && y < grid.height);

            assert x + nx < grid.length && y + ny < grid.height;
            assert x + nx >= 0 && y + ny >= 0;

            this.ball = new Ball(x, y, nx, ny, grid.length, grid.height);
            this.grid = grid;
            this.semaphore = sempahore;
        }

        public BallRunnable(Ball ball, Grid grid, Semaphore semaphore) {
            this.ball = ball;
            this.grid = grid;
            this.semaphore = semaphore;
        }

        @Override
        public void run() {
            try {
                while (!Thread.interrupted()) {
                    Thread.sleep(20);
                    print(ball, grid);

                    int x = ball.x + ball.nx * 2;
                    int y = ball.y + ball.ny * 2;

                    var nx = ball.nx;
                    var ny = ball.ny;

                    if (x < 0 || x >= grid.length) {
                        nx = -nx;
                    }
                    if (y < 0 || y >= grid.height) {
                        ny = -ny;
                    }

                    // if the next ball location is on the boundary of the box,
                    // check if we have already acquired the permit,
                    //      if so we are inside the box, make a bounce off its boundary
                    //      if no try to acquire the permit
                    //          if the permit acquired enter the box
                    //          if no bounce off the box's boundary

                    var isOnBoxBoundaryXY = grid.isOnBoxBoundaryX(x, y);
                    var isOnBoxBoundaryYX = grid.isOnBoxBoundaryY(x, y);

                    if (isOnBoxBoundaryXY || isOnBoxBoundaryYX) {
                        boolean canBounce = true;
                        if (isInsideBox
                                && countBouncesInsideAgainstBox >= bouncesInsideAgainstBox) {
                            countBouncesInsideAgainstBox = 0;
                            ball.sign = '○';
                            canBounce = false;
                            semaphore.release();

                            isInsideBox = false;
                        } else if (!isInsideBox
                                && countBouncesOutsideAgainstBox >= bouncesOutsideAgainstBox
                                // dont allow corners of the box to be treated as entrace in the box
                                && !(isOnBoxBoundaryXY && isOnBoxBoundaryYX)
                                && semaphore.tryAcquire()) {
                            countBouncesOutsideAgainstBox = 0;
                            ball.sign = '●';
                            canBounce = false;

                            isInsideBox = true;
                        }

                        if (canBounce) {
                            // if the next cell is a corner
                            if (isOnBoxBoundaryXY && isOnBoxBoundaryYX) {
                                // if inside of the box reverse the direction
                                if (isInsideBox) {
                                    nx = -nx;
                                    ny = -ny;
                                } else if (grid.isOnBoxBoundaryX(ball.x + ball.nx)) {
                                    ny = -ny;
                                } else {
                                    nx = -nx;
                                }
                            } else if (isOnBoxBoundaryXY) {
                                nx = -nx;
                            } else if (isOnBoxBoundaryYX) {
                                ny = -ny;
                            }

                            if (isInsideBox) {
                                countBouncesInsideAgainstBox++;
                            } else {
                                countBouncesOutsideAgainstBox++;
                            }
                        }
                    }

                    ball.set(ball.x + ball.nx, ball.y + ball.ny, nx, ny);
                    if (((ball.x >= 0 && ball.x < grid.boxLength)
                                    || (ball.x >= grid.length - grid.boxLength
                                            && ball.x < grid.length))
                            && ((ball.y >= 0 && ball.y < grid.boxHeigth)
                                    || (ball.y >= grid.height - grid.boxHeigth
                                            && ball.y < grid.height))
                            && ball.sign == '●') {

                        // print(ball,grid);
                        System.out.printf(
                                "bx %s by %s | x %s y %s | px %s py %s %s %s %s %s ball.ny %s y %s"
                                        + " %n",
                                ball.x,
                                ball.y,
                                x,
                                y,
                                ball.px,
                                ball.py,
                                isOnBoxBoundaryXY,
                                isOnBoxBoundaryYX,
                                grid.length - grid.boxLength - 1,
                                grid.height - grid.boxHeigth - 1,
                                ball.ny,
                                ball.y);
                        System.exit(1);
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        int length = 50, height = 20, balls = 30, ballInBox = balls / 3;

        if (args.length >= 2) {
            length = Integer.parseInt(args[0]);
            height = Integer.parseInt(args[1]);
        }
        if (args.length >= 3) {
            balls = Integer.parseInt(args[2]);
        }

        ballInBox = balls / 3;

        System.out.printf(
                "Bounds: length: %s height: %s balls: %s box can fit balls: %s %n",
                length, height, balls, ballInBox);

        var semaphore = new Semaphore(ballInBox);
        var grid = new Grid(length, height);
        printGrid(grid);

        var ex = (ThreadPoolExecutor) Executors.newCachedThreadPool();

        if (args.length == 7) {
            for (int i = 0; i < balls; i++) {
                ex.submit(
                        new BallRunnable(
                                new Ball(
                                        Integer.parseInt(args[3]),
                                        Integer.parseInt(args[4]),
                                        Integer.parseInt(args[5]),
                                        Integer.parseInt(args[6]),
                                        grid.length,
                                        grid.height),
                                grid,
                                semaphore));
            }
        } else {
            for (int i = 0; i < balls; i++) {
                ex.submit(new BallRunnable(grid, semaphore));
            }
        }
        while (ex.getActiveCount() != 0) {
            Thread.sleep(100);
        }
        ex.shutdownNow();
    }

    private static synchronized void print(Ball ball, Grid grid) {
        StringBuilder sb = new StringBuilder();

        // acount for an empty line below the grid and bottom line of the grid
        sb.append("\033[%sA".formatted(ball.py + 2));
        // account for left line of the grid
        sb.append("\033[%sC".formatted(ball.px + 1));

        var sign = ' ';
        if (grid.isOnBoxBoundaryX(ball.px, ball.py)) {
            sign = '|';
        } else if (grid.isOnBoxBoundaryY(ball.px, ball.py)) {
            sign = '-';
        }

        sb.append(sign);
        sb.append("\033[%sB".formatted(ball.py + 2));
        sb.append("\r");
        sb.append("\033[%sA".formatted(ball.y + 2));
        sb.append("\033[%sC".formatted(ball.x + 1));
        sb.append(ball.sign);
        sb.append("\033[%sB".formatted(ball.y + 2));
        sb.append("\r");

        System.out.print(sb.toString());
    }

    private static void printGrid(Grid grid) {

        var edgeLine = "-".repeat(grid.length);
        var midLine = " ".repeat(grid.length);
        // -2 to compensate for two sides
        var centerEdgeBox = "-".repeat(grid.length - grid.boxLength * 2 - 2);
        var centerMidBox = " ".repeat(grid.length - grid.boxLength * 2 - 2);
        var horizonEdgeBox = " ".repeat(grid.boxLength);

        var top = "┌%s┐".formatted(edgeLine);
        var mid = "|%s|".formatted(midLine);
        var bot = "└%s┘".formatted(edgeLine);

        var topBox = "|%s┌%s┐%s|".formatted(horizonEdgeBox, centerEdgeBox, horizonEdgeBox);
        var midBox = "|%s|%s|%s|".formatted(horizonEdgeBox, centerMidBox, horizonEdgeBox);
        var botBox = "|%s└%s┘%s|".formatted(horizonEdgeBox, centerEdgeBox, horizonEdgeBox);

        System.out.println(top);
        for (int i = 0; i < grid.height; i++) {
            if (i == grid.boxHeigth) {
                System.out.println(topBox);
            } else if (i >= grid.boxHeigth && i < grid.height - grid.boxHeigth - 1) {
                System.out.println(midBox);
            } else if (i == grid.height - grid.boxHeigth - 1) {
                System.out.println(botBox);
            } else {
                System.out.println(mid);
            }
        }
        System.out.println(bot);
    }
}
