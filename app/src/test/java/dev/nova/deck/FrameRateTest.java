package dev.nova.deck;
import org.junit.Test;
import static org.junit.Assert.*;
public class FrameRateTest {
    private String stream(long start,long interval,int count){StringBuilder s=new StringBuilder("8333333\n");for(int i=0;i<count;i++)s.append("1\t").append(start+i*interval).append("\t2\n");return s.toString();}
    @Test public void countsAppPresentationsRatherThanPanelRefreshPeriod(){
        assertEquals(30f,FrameRate.fromLatency(stream(1000000000L,33333333L,31),2100000000L),.1f);
        assertEquals(24f,FrameRate.fromLatency(stream(1000000000L,41666667L,25),2100000000L),.1f);
    }
    @Test public void rejectsInvalidFencesAndDoesNotReportStaleFramesAsCurrentFps(){
        assertTrue(Float.isNaN(FrameRate.fromLatency("8333333\n0 0 0\n0 9223372036854775807 0\n",2000000000L)));
        assertEquals(0f,FrameRate.fromLatency(stream(1000000000L,33333333L,31),5000000000L),0f);
    }
}
