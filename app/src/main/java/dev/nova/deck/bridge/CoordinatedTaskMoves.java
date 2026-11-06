package dev.nova.deck.bridge;

import dev.nova.deck.ControlLogic;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Starts a two-screen swap together so Android has less time with only one task moved. */
final class CoordinatedTaskMoves {
    interface Mover { void move(ControlLogic.Move move) throws Exception; }

    private CoordinatedTaskMoves() {}

    static void execute(List<ControlLogic.Move> moves, Mover mover) throws Exception {
        if (moves.size() < 2) {
            for (ControlLogic.Move move : moves) mover.move(move);
            return;
        }
        if (moves.size() != 2) throw new IllegalArgumentException("A screen swap can move at most two tasks");

        ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "DuoDash-screen-swap");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> pending = new ArrayList<>(2);
            for (ControlLogic.Move move : moves) pending.add(workers.submit(() -> {
                mover.move(move);
                return null;
            }));

            Exception firstFailure = null;
            boolean interrupted = false;
            // Join both calls even if one fails. The caller must inspect actual task placement
            // before rolling anything back; the other binder call may already have completed.
            for (Future<?> future : pending) {
                try {
                    future.get();
                } catch (InterruptedException failure) {
                    interrupted = true;
                    if (firstFailure == null) firstFailure = failure;
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    Exception normalized = cause instanceof Exception
                        ? (Exception) cause : new RuntimeException(cause);
                    if (firstFailure == null) firstFailure = normalized;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (firstFailure != null) throw firstFailure;
        } finally {
            workers.shutdown();
        }
    }
}
