package dev.nova.deck.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import dev.nova.deck.ControlLogic;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class CoordinatedTaskMovesTest {
    @Test public void twoTaskSwapStartsBothMovesBeforeJoiningEither() throws Exception {
        List<ControlLogic.Move> moves=List.of(
            new ControlLogic.Move(41,0,4),new ControlLogic.Move(72,4,0));
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        AtomicInteger completed=new AtomicInteger();
        Thread caller=new Thread(()->{
            try{CoordinatedTaskMoves.execute(moves,move->{entered.countDown();if(!release.await(2,TimeUnit.SECONDS))throw new AssertionError("test release timed out");completed.incrementAndGet();});}
            catch(Exception failure){throw new AssertionError(failure);}
        });
        caller.start();
        assertTrue("both moves should be in flight together",entered.await(1,TimeUnit.SECONDS));
        assertEquals(0,completed.get());
        release.countDown();
        caller.join(2000);
        assertFalse("coordinated move call should finish",caller.isAlive());
        assertEquals(2,completed.get());
    }

    @Test public void singleTaskMoveRemainsDirectAndSequential() throws Exception {
        AtomicBoolean called=new AtomicBoolean();
        CoordinatedTaskMoves.execute(List.of(new ControlLogic.Move(12,0,4)),move->called.set(true));
        assertTrue(called.get());
    }

    @Test public void waitsForOtherMoveBeforeReturningWhenOneMoveFails() throws Exception {
        List<ControlLogic.Move> moves=List.of(
            new ControlLogic.Move(41,0,4),new ControlLogic.Move(72,4,0));
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        AtomicBoolean returned=new AtomicBoolean();
        Thread caller=new Thread(()->{
            try{CoordinatedTaskMoves.execute(moves,move->{
                entered.countDown();
                if(move.task==41)throw new IllegalStateException("expected test failure");
                if(!release.await(2,TimeUnit.SECONDS))throw new AssertionError("test release timed out");
            });}catch(Exception expected){returned.set(true);}
        });
        caller.start();
        assertTrue(entered.await(1,TimeUnit.SECONDS));
        Thread.sleep(50);
        assertFalse("failure must wait until the other move completes",returned.get());
        release.countDown();
        caller.join(2000);
        assertTrue(returned.get());
    }
}
