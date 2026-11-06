package dev.nova.deck;

import org.junit.Test;
import static org.junit.Assert.*;

public class DashboardGridSizingPolicyTest {
    @Test public void threeRowsFillSpaceAfterFixedSectionsAndGutters(){
        int available=DashboardGridSizingPolicy.availableGridHeight(414,12,0,44,0,0,172,6,0,8,0,0);
        assertEquals(172,available);
        int row=DashboardGridSizingPolicy.rowHeight(available,3,7,48,48,true);
        assertEquals(52,row);
        assertEquals(170,DashboardGridSizingPolicy.gridHeight(3,row,7));
    }

    @Test public void fourRowsKeepThreeRowFitHeightAndOverflowIntoScroll(){
        int row=DashboardGridSizingPolicy.rowHeight(172,4,7,48,48,true);
        assertEquals(52,row);
        assertTrue(DashboardGridSizingPolicy.gridHeight(4,row,7)>172);
    }

    @Test public void smallViewportKeepsNaturalMinimumWithoutNegativeSizing(){
        assertEquals(48,DashboardGridSizingPolicy.rowHeight(20,3,7,48,60,true));
        assertEquals(0,DashboardGridSizingPolicy.availableGridHeight(10,20,20,44,0,0,100,6,0,8,0,0));
    }

    @Test public void floatingPanelsKeepTheirNaturalCompactRows(){
        assertEquals(48,DashboardGridSizingPolicy.rowHeight(600,3,7,48,48,false));
        assertEquals(60,DashboardGridSizingPolicy.rowHeight(200,5,7,48,60,false));
    }
}
