package dev.nova.deck.bridge;

import org.junit.Test;
import java.util.Arrays;
import dev.nova.deck.ControlLogic;
import static org.junit.Assert.*;

public final class PanelFocusLeaseTest {
    @Test public void acquireRecordsPreviousTaskAndReleaseRestoresIt(){
        PanelFocusLease lease=new PanelFocusLease();
        assertEquals(24,lease.acquire(11,24,0));
        assertTrue(lease.active());
        assertEquals(24,lease.targetTaskId());
        assertEquals(0,lease.displayId());
        assertEquals(11,lease.release(24));
        assertFalse(lease.active());
    }

    @Test public void repeatedAcquireForSameTargetPreservesOriginalRestoreTarget(){
        PanelFocusLease lease=new PanelFocusLease();
        assertEquals(24,lease.acquire(11,24,0));
        assertEquals(-1,lease.acquire(24,24,0));
        assertEquals(11,lease.release(24));
    }

    @Test public void releaseDoesNotStealFocusAfterUserChangesTasks(){
        PanelFocusLease lease=new PanelFocusLease();
        assertEquals(24,lease.acquire(11,24,0));
        assertEquals(-1,lease.release(31));
        assertFalse(lease.active());
    }

    @Test public void alreadyFocusedTargetAndInvalidTasksNeedNoLease(){
        PanelFocusLease lease=new PanelFocusLease();
        assertEquals(-1,lease.acquire(24,24,0));
        assertEquals(-1,lease.acquire(-1,24,0));
        assertEquals(-1,lease.acquire(11,-1,0));
        assertFalse(lease.active());
    }

    @Test public void userFocusChangePreventsRepeatedOpenFromReacquiring(){
        PanelFocusLease lease=new PanelFocusLease();
        assertEquals(24,lease.acquire(11,24,0));
        assertEquals(-1,lease.acquire(31,24,0));
        assertFalse(lease.active());
        assertEquals(-1,lease.release(31));
    }

    @Test public void targetSelectionIncludesVisibleLauncherAndUsesTopmostVisibleRoot(){
        assertEquals(7,PanelFocusLease.targetTask(Arrays.asList(
            new ControlLogic.Task(7,0,"com.android.launcher",true,false),
            new ControlLogic.Task(8,0,"com.example.game",true,true)),0));
    }

    @Test public void targetSelectionRejectsInvisibleRootsAndOtherDisplays(){
        assertEquals(-1,PanelFocusLease.targetTask(Arrays.asList(
            new ControlLogic.Task(7,0,"com.android.launcher",false,false),
            new ControlLogic.Task(8,6,"dev.nova.deck",true,false)),0));
    }
}
