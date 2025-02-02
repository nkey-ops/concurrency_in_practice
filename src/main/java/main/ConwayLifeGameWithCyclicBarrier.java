package main;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Conway's Game of Life is a cellular automaton devised by the British mathematician John
 * Conway in 1970. It's a simulation of life based on a few simple rules, but it can produce
 * incredibly complex patterns. The game is played on a grid of cells, where each cell can be either
 * "alive" or "dead."
 *
 * <p>Rules of Conway's Game of Life: Grid:
 *
 * <p>The game is played on a grid (usually rectangular) where each cell in the grid can either be
 * "alive" (represented by a living cell) or "dead" (represented by an empty cell). The state of
 * each cell is updated simultaneously during each "generation." Neighbors:
 *
 * <p>Each cell has 8 neighbors: the 8 adjacent cells surrounding it (including diagonals). For
 * example, a cell in the center has 8 neighbors, while a cell on the edge of the grid has fewer
 * neighbors. Rules for cell evolution:
 *
 * <p>Survival: If a living cell has 2 or 3 neighbors, it remains alive in the next generation. If
 * it has fewer than 2 neighbors, it dies due to underpopulation. If it has more than 3 neighbors,
 * it dies due to overpopulation. Birth: If a dead cell has exactly 3 living neighbors, it becomes
 * alive in the next generation, representing reproduction or birth. Death: If a cell doesn’t meet
 * the criteria for survival or birth, it dies (either from underpopulation, overpopulation, or
 * remains dead). Boundary Conditions:
 *
 * <p>The grid is often treated as having wrapping boundaries, meaning cells on the edge of the grid
 * have neighbors that "wrap around" to the opposite edge of the grid. For example, the cell in the
 * top-left corner might have neighbors from the bottom and right edges. Alternatively, the grid may
 * have fixed boundaries, meaning cells outside the grid don't have neighbors. Generations:
 *
 * <p>The game progresses in discrete "generations" or time steps. After each generation, all cells
 * update their state simultaneously based on the number of neighbors.
 *
 *
 * Solution: 
 * <p>Values read from a current board and according to the rules set on a new board, boards are
 * swapped after each generation
 *
 * <p>A board is split into subboards equal to number of cpu availbale and sent to each worker
 * thread to parse it.
 *
 * <p>Each worker will share a cyclic barier to signal that it is done with parcing its subboard and
 * is ready to parce a new generation when all workers signaled that they are done, one worker make
 * the board swap old table with a new and start a another generation
 */
public class ConwayLifeGameWithCyclicBarrier {

    private static class Board {
        private boolean[][] currentGen;
        private boolean[][] newGen;
        private final int length;

        private class SubBoard {

            private final int sx;
            private final int sy;
            private final int maxX;
            private final int maxY;

            public SubBoard(int sx, int ex, int sy, int ey) {
                this.sx = sx;
                this.sy = sy;
                this.maxX = ex - sx;
                this.maxY = ey - sy;
            }

            public int countAlive(int x, int y) {
                if (x < 0 || x >= maxX) {
                    throw new IllegalArgumentException(
                            "x %s cannot be below 0 or more or equal than %s".formatted(x, maxX));
                }
                if (y < 0 || y >= maxY) {
                    throw new IllegalArgumentException(
                            "y %s cannot be below 0 or more or equal than %s".formatted(x, maxY));
                }

                var aliveCellsCounter = 0;

                var di = new int[] {-1, 0, 1};

                for (int dy = 0; dy < di.length; dy++) {
                    for (int dx = 0; dx < di.length; dx++) {
                        if (dy == 1 && dx == 1) continue; // if the cell to check is the same cell

                        var nx = di[dx] + x;
                        var ny = di[dy] + y;

                        if (sx + nx < 0 || sx + nx >= length || sy + ny < 0 || sy + ny >= length) continue;
                        if (currentGen[sy + ny][sx + nx]) aliveCellsCounter++;
                    }
                }

                return aliveCellsCounter;
            }

            public boolean isAlive(int x, int y) {
                if (x < 0 || x >= maxX) {
                    throw new IllegalArgumentException(
                            "x %s cannot be below 0 or more or equal than %s".formatted(x, maxX));
                }
                if (y < 0 || y >= maxY) {
                    throw new IllegalArgumentException(
                            "y %s cannot be below 0 or more or equal than %s".formatted(x, maxY));
                }

                return currentGen[sy + y][sx + x];
            }

            public void setValue(int x, int y, boolean isAlive) {
                if (x < 0 || x >= maxX) {
                    throw new IllegalArgumentException(
                            "x %s cannot be below 0 or more or equal than %s".formatted(x, maxX));
                }
                if (y < 0 || y >= maxY) {
                    throw new IllegalArgumentException(
                            "y %s cannot be below 0 or more or equal than %s".formatted(x, maxY));
                }

                newGen[sy + y][sx + x] = isAlive;
            }

            @Override
            public String toString() {
                return "SubBoard [sx="
                        + sx
                        + ", sy="
                        + sy
                        + ", maxX="
                        + maxX
                        + ", maxY="
                        + maxY
                        + "]";
            }
        }

        public Board(int length, float aliveCellRate) {
            if (length < 1) {
                throw new IllegalArgumentException("length %s cannot be below 1".formatted(length));
            }
            if (aliveCellRate <= 0 || aliveCellRate >= 1) {
                throw new IllegalArgumentException(
                        "aliveCellRate %s cannot be below or equal to 0 and more or equal to 1"
                                .formatted(aliveCellRate));
            }

            this.length = length;
            this.currentGen = new boolean[length][length];
            this.newGen = new boolean[length][length];

            for (int y = 0; y < length; y++) {
                for (int x = 0; x < length; x++) {
                    if (Math.random() <= aliveCellRate) {
                        currentGen[y][x] = true;
                    }
                }
            }
        }

        public SubBoard[] getSubBoards(int boards) {
            if (boards < 1 || length / boards < 3) {
                throw new IllegalArgumentException(
                        "number of subboards %s should more than 0 and smaller than %s"
                                .formatted(boards, length / 3 + 1));
            }
            var lb = length / boards;
            var subboards = new SubBoard[boards];

            for (int i = 0; i < boards - 1; i++) {
                subboards[i] = new SubBoard(0, length, i * lb, i * lb + lb);
            }

            subboards[boards - 1] = new SubBoard(0, length, (boards - 1) * lb, length);
            return subboards;
        }

        public void swap() {
            var tmp = currentGen;
            currentGen = newGen;
            newGen = tmp;
        }

        @Override
        public String toString() {
            var sb = new StringBuilder();
            for (int y = 0; y < length; y++) {
                for (int x = 0; x < length; x++) {
                    sb.append(currentGen[y][x] ? '●' : '○');
                    if (x != length - 1) {
                        sb.append(' ');
                    }
                }
                if(y != length -1){
                    sb.append(System.lineSeparator());
                }
            }
            return sb.toString();
        }
    }

    private static class Worker implements Runnable {

        private CyclicBarrier cBarrier;
        private Board.SubBoard subboard;

        public Worker(Board.SubBoard subboard, CyclicBarrier cBarrier) {
            this.subboard = subboard;
            this.cBarrier = cBarrier;
        }

        @Override
        public void run() {
            try {
                while (!Thread.interrupted()) {
                    for (int y = 0; y < subboard.maxY; y++) {
                        for (int x = 0; x < subboard.maxX; x++) {
                            var alive = subboard.countAlive(x, y);
                            if (alive < 2 || alive > 3) {
                                subboard.setValue(x, y, false);
                            } else if (!subboard.isAlive(x, y) && alive == 3) {
                                subboard.setValue(x, y, true);
                            }else {
                                subboard.setValue(x, y, subboard.isAlive(x, y));
                            }
                        }
                    }
                    cBarrier.await();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        int length = args.length >= 1 ? Integer.parseInt(args[0]) : 30;
        float alliveCellRate = args.length >= 2 ? Float.parseFloat(args[1]) : 0.4f;

        var board = new Board(length, alliveCellRate);
        var subboards = board.getSubBoards(Runtime.getRuntime().availableProcessors());
        var ex = Executors.newFixedThreadPool(subboards.length);

        // there is no need in atomicity, using it just because the lambda expresion requires a
        // final object
        var gen = new AtomicInteger();
        var cyclicBarrier =
                new CyclicBarrier(
                        subboards.length,
                        () -> {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            board.swap();
                            System.out.printf("\r\033[%sA", length);
                            System.out.println(board);
                            System.out.print("Generation: #" + gen.incrementAndGet());
                        });

        System.out.println(board);
        System.out.print("Generation: #" + gen.incrementAndGet());

        for (int i = 0; i < subboards.length; i++) {
            ex.submit(new Worker(subboards[i], cyclicBarrier));
        }
    }
}
