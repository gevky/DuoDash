package dev.nova.deck.bridge;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class DockedCanvasLeaseTest {
    @Test public void restoresExactPreexistingOverrideAndKeepsOriginalAcrossResizes() throws Exception {
        FakeShell shell=new FakeShell("1440x1080");
        DockedCanvasLease lease=new DockedCanvasLease(shell);

        lease.apply("tv:living-room",1280,720,0);
        lease.apply("tv:living-room",1920,1080,0);
        assertEquals("1920x1080",shell.override);
        lease.release();

        assertEquals("1440x1080",shell.override);
        assertFalse(lease.active());
    }

    @Test public void restoresNativeSizeWhenThereWasNoOverride() throws Exception {
        FakeShell shell=new FakeShell(null);
        DockedCanvasLease lease=new DockedCanvasLease(shell);
        lease.apply("glasses",1280,720,1);
        assertEquals("720x1280",shell.override);

        lease.release();

        assertNull(shell.override);
        assertTrue(shell.commands.contains("wm size reset -d 0"));
    }

    @Test public void changingOutputRestoresOldLeaseBeforeSnapshottingNewOne() throws Exception {
        FakeShell shell=new FakeShell("1600x900");
        DockedCanvasLease lease=new DockedCanvasLease(shell);
        lease.apply("tv-a",1280,720,0);
        lease.apply("tv-b",1440,1080,0);
        assertEquals("tv-b",lease.identity());
        lease.release();

        assertEquals("1600x900",shell.override);
        assertNull(lease.identity());
    }

    @Test public void invalidDimensionsDoNotRunShellCommands() throws Exception {
        FakeShell shell=new FakeShell(null);
        DockedCanvasLease lease=new DockedCanvasLease(shell);
        try { lease.apply("tv",319,720,0); fail("Expected invalid dimensions"); }
        catch(IllegalArgumentException expected) { }
        assertTrue(shell.commands.isEmpty());
    }

    @Test public void failedResizeRestoresBaselineAndKeepsLeaseRetryableWhenRestoreFails() throws Exception {
        FakeShell shell=new FakeShell("1440x1080");
        DockedCanvasLease lease=new DockedCanvasLease(shell);
        shell.sizeWriteFailures=2;
        try { lease.apply("tv",1280,720,0); fail("Expected resize failure"); }
        catch(IOException expected) { }
        assertEquals("1440x1080",shell.override);
        assertTrue(lease.active());
        lease.release();
        assertFalse(lease.active());
    }

    @Test public void releaseLeavesUnownedManualOverrideAlone() throws Exception {
        FakeShell shell=new FakeShell(null);
        DockedCanvasLease lease=new DockedCanvasLease(shell);
        lease.apply("tv",1280,720,0);
        shell.override="1024x768";

        lease.release();

        assertEquals("1024x768",shell.override);
    }

    @Test public void applyDoesNotOverwriteAnUnownedManualOverride() throws Exception {
        FakeShell shell=new FakeShell(null);
        DockedCanvasLease lease=new DockedCanvasLease(shell);
        lease.apply("tv",1280,720,0);
        shell.override="1024x768";

        try { lease.apply("tv",1920,1080,0); fail("Expected ownership loss"); }
        catch(IOException expected) { }

        assertEquals("1024x768",shell.override);
        assertFalse(lease.active());
    }

    @Test public void restartedOwnerRecoversOriginalBaselineBeforeApplyingAgain() throws Exception {
        FakeShell shell=new FakeShell("1440x1080");
        DockedCanvasLease oldOwner=new DockedCanvasLease(shell);
        oldOwner.apply("tv",1280,720,0);
        // Simulate helper death: the device state and independent watcher survive the Java object.
        DockedCanvasLease restartedOwner=new DockedCanvasLease(shell);
        restartedOwner.apply("tv",1920,1080,0);
        assertEquals("1920x1080",shell.override);

        restartedOwner.release();

        assertEquals("1440x1080",shell.override);
    }

    @Test public void restartedOwnerPreservesNativeBaselineWhenThereWasNoOverride() throws Exception {
        FakeShell shell=new FakeShell(null);
        DockedCanvasLease oldOwner=new DockedCanvasLease(shell);
        oldOwner.apply("glasses",1280,720,0);
        DockedCanvasLease restartedOwner=new DockedCanvasLease(shell);

        restartedOwner.apply("glasses",1920,1080,0);
        restartedOwner.release();

        assertNull(shell.override);
    }

    private static final class FakeShell implements DockedCanvasLease.Shell {
        String override;
        String savedState="";
        int sizeWriteFailures;
        final List<String> commands=new ArrayList<>();
        FakeShell(String initial){override=initial;}
        @Override public String run(String...args)throws Exception{
            String command=String.join(" ",args);commands.add(command);
            if(args.length>=3&&args[0].equals("rm")&&args[1].equals("-f")){savedState="";return "";}
            if(args.length==3&&args[0].equals("sh")&&args[1].equals("-c")){
                if(args[2].startsWith("cat /data/local/tmp/duodash-docked-canvas-state"))return savedState;
                java.util.regex.Matcher state=java.util.regex.Pattern.compile("printf '%s\\\\n' '([RO])' '(\\d+)' '(\\d+)' '([^']+)' '([^']+)' \\\"\\$\\(\\(.*?\\)\\)\\\" > /data/local/tmp/duodash-docked-canvas-state\\.new").matcher(args[2]);
                if(state.find())savedState=state.group(1)+"\n"+state.group(2)+"\n"+state.group(3)+"\n"+state.group(4)+"\n"+state.group(5)+"\n30\n";
                return "";
            }
            if(args.length==4&&args[0].equals("wm")&&args[1].equals("size")&&args[2].equals("-d"))
                return "Physical size: 2400x1800\n"+(override==null?"":"Override size: "+override+"\n");
            if(args.length>=4&&args[0].equals("wm")&&args[1].equals("size")){
                if(sizeWriteFailures>0){sizeWriteFailures--;throw new IOException("injected write failure");}
                if(args[2].equals("reset"))override=null;
                else if(args[2].matches("[0-9]+x[0-9]+"))override=args[2];
                return "";
            }
            return "";
        }
    }
}
