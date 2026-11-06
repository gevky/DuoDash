package dev.nova.deck;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ControlSizePolicyTest {
    @Test public void ignoresMetricsAndResolvesConflictingSizeAsWide(){
        assertEquals(ControlSizePolicy.WIDE,ControlSizePolicy.mode("wifi","wifi","wifi"));
        ControlSizePolicy.Sizes sizes=ControlSizePolicy.resize("cpu","wifi","bluetooth","",4,true);
        assertFalse(sizes.changed);assertEquals("wifi",sizes.small);assertEquals("bluetooth",sizes.wide);assertEquals("",sizes.explicit);
    }
    @Test public void mixedSizesPackInOrderAndNeverCrossARow(){
        List<ControlLogic.GridPlacement> p=ControlSizePolicy.pack(Arrays.asList("wifi","bluetooth","dark","apps"),"wifi,bluetooth","apps","",2,1);
        assertEquals(6,p.get(0).span);assertEquals(0,p.get(0).column);
        assertEquals(6,p.get(1).column);assertEquals(12,p.get(2).span);assertEquals(12,p.get(2).column);
        assertEquals(1,p.get(3).row);assertEquals(24,p.get(3).span);
    }
    @Test public void narrowLayoutsKeepSmallButtonsAtFullSlotWidth(){
        List<ControlLogic.GridPlacement> p=ControlSizePolicy.pack(Arrays.asList("wifi","bluetooth","dark"),"wifi,bluetooth","","",2,7);
        assertEquals(12,p.get(0).span);assertEquals(12,p.get(1).column);assertEquals(1,p.get(2).row);
    }
    @Test public void deletedAppSizeMetadataIsDiscarded(){
        String app="app_0123456789abcdef01234567";
        assertEquals("wifi",ControlSizePolicy.retainSmall("wifi,"+app,Collections.emptyList()));
        assertEquals("wifi,"+app,ControlSizePolicy.retainSmall("wifi,"+app,Arrays.asList(app)));
    }
    @Test public void optionalSharedFieldDoesNotRequireLegacyMigration(){
        assertEquals("sharedDashboard:small",SharedDashboardLayout.storageKey("handheld",SharedDashboardLayout.SMALL,true));
        assertEquals("handheld:small",SharedDashboardLayout.storageKey("handheld",SharedDashboardLayout.SMALL,false));
    }
    @Test public void packingStaysWithinEverySupportedColumnCount(){
        List<String> controls=Arrays.asList("wifi","bluetooth","dark","apps","swap","up","down","fan","performance","rotation","dnd","keyboard");
        for(int columns=2;columns<=4;columns++){
            List<ControlLogic.GridPlacement> placements=ControlSizePolicy.pack(controls,"wifi,bluetooth,fan","apps,swap","",columns,1);
            assertEquals(controls.size(),placements.size());
            for(int i=0;i<placements.size();i++){
                ControlLogic.GridPlacement p=placements.get(i);assertEquals(controls.get(i),p.id);
                assertTrue(p.column>=0);assertTrue(p.column+p.span<=24);
                if(i>0){ControlLogic.GridPlacement previous=placements.get(i-1);assertTrue(p.row>previous.row||p.column>=previous.column+previous.span);}
            }
        }
    }

    @Test public void legacyWidthsKeepTheirTwoThreeAndFourColumnProportions(){
        for(int columns=2;columns<=4;columns++){
            List<ControlLogic.GridPlacement> p=ControlSizePolicy.pack(Arrays.asList("wifi","bluetooth","dark"),"wifi","dark","",columns,1);
            assertEquals(12/columns,p.get(0).span);
            assertEquals(24/columns,p.get(1).span);
            assertEquals(48/columns,p.get(2).span);
        }
    }

    @Test public void explicitSizeCatalogValidatesAndPacksFromOneSixthToFullRow(){
        String sizes=ControlSizePolicy.normalizeSizes("wifi:1,bluetooth:6,fan:2,cpu:3,wifi:4,dark:0,apps:7");
        assertEquals("wifi:4,bluetooth:6,fan:2",sizes);
        assertEquals(4,ControlSizePolicy.explicitSize("wifi",sizes));
        assertEquals(0,ControlSizePolicy.explicitSize("rotation",sizes));
        List<ControlLogic.GridPlacement> p=ControlSizePolicy.pack(Arrays.asList("wifi","bluetooth","dark"),"","","wifi:1,bluetooth:6",4,1);
        assertEquals(4,p.get(0).span);assertEquals(0,p.get(0).row);
        assertEquals(24,p.get(1).span);assertEquals(1,p.get(1).row);
        assertEquals(2,p.get(2).row);assertEquals(0,p.get(2).column);
    }

    @Test public void directionalResizeStepsFromCurrentLegacyWidthWithoutChangingItUntilResize(){
        ControlSizePolicy.Sizes shrink=ControlSizePolicy.resize("wifi","","","",4,false);
        ControlSizePolicy.Sizes grow=ControlSizePolicy.resize("wifi","","","",4,true);
        assertEquals("wifi:1",shrink.explicit); // Legacy normal is 6 units; nearest smaller is 4.
        assertEquals("wifi:2",grow.explicit); // Nearest larger is 8 units.
        ControlSizePolicy.Sizes smaller=ControlSizePolicy.resize("wifi","","","",2,false);
        ControlSizePolicy.Sizes larger=ControlSizePolicy.resize("wifi","","","",2,true);
        assertEquals("wifi:2",smaller.explicit);assertEquals("wifi:4",larger.explicit);
    }

    @Test public void legacySmallAndFullRowRespectDirectionalStops(){
        ControlSizePolicy.Sizes legacyTinyLeft=ControlSizePolicy.resize("wifi","wifi","","",4,false);
        ControlSizePolicy.Sizes legacyTinyRight=ControlSizePolicy.resize("wifi","wifi","","",4,true);
        assertEquals("wifi",legacyTinyLeft.small);assertEquals("",legacyTinyLeft.explicit);
        assertEquals("wifi:1",legacyTinyRight.explicit);
        ControlSizePolicy.Sizes legacyFullRight=ControlSizePolicy.resize("wifi","","wifi","",2,true);
        ControlSizePolicy.Sizes legacyFullLeft=ControlSizePolicy.resize("wifi","","wifi","",2,false);
        assertEquals("wifi",legacyFullRight.wide);assertEquals("",legacyFullRight.explicit);
        assertEquals("wifi:5",legacyFullLeft.explicit);
    }

    @Test public void explicitResizeClampsAtOneAndSixWithoutWrapping(){
        ControlSizePolicy.Sizes leftAtOne=ControlSizePolicy.resize("wifi","","","wifi:1",4,false);
        ControlSizePolicy.Sizes rightAtSix=ControlSizePolicy.resize("wifi","","","wifi:6",4,true);
        ControlSizePolicy.Sizes leftAtSix=ControlSizePolicy.resize("wifi","","","wifi:6",4,false);
        ControlSizePolicy.Sizes rightAtOne=ControlSizePolicy.resize("wifi","","","wifi:1",4,true);
        assertEquals("wifi:1",leftAtOne.explicit);assertEquals("wifi:6",rightAtSix.explicit);
        assertFalse(leftAtOne.changed);assertFalse(rightAtSix.changed);
        assertEquals("wifi:5",leftAtSix.explicit);assertEquals("wifi:2",rightAtOne.explicit);
    }

    @Test public void editTapDirectionUsesTheActualTileHalf(){
        assertFalse(ControlSizePolicy.growFromTap(20,100));
        assertTrue(ControlSizePolicy.growFromTap(80,100));
        assertTrue(ControlSizePolicy.growFromTap(50,100));
        assertFalse(ControlSizePolicy.growFromTap(40,0));
    }

    @Test public void smallestTileGrowsFromEitherHalfIncludingLegacySmall(){
        assertTrue(ControlSizePolicy.tapGrows("wifi",10,100,"","","wifi:1",4));
        assertTrue(ControlSizePolicy.tapGrows("wifi",90,100,"","","wifi:1",4));
        assertTrue(ControlSizePolicy.tapGrows("wifi",10,100,"wifi","","",4));
        assertFalse(ControlSizePolicy.tapGrows("wifi",10,100,"","","wifi:2",4));
        assertFalse(ControlSizePolicy.tapGrows("wifi",10,100,"","","",4));
        assertTrue(ControlSizePolicy.tapGrows("wifi",90,100,"","","",4));
    }

    @Test public void narrowExplicitAndLegacySmallControlsFallBackToAtLeastTheTargetWidth(){
        List<ControlLogic.GridPlacement> placements=ControlSizePolicy.pack(Arrays.asList("wifi","bluetooth"),"bluetooth","","wifi:1",4,7);
        assertEquals(7,placements.get(0).span);
        assertEquals(7,placements.get(1).span);
    }

    @Test public void fullRowSpanEndsExactlyAtGridBoundaryIncludingGutters(){
        for(int width:new int[]{407,960,1280}){
            int gap=5;
            assertEquals(width,ControlSizePolicy.unitEdge(width,gap,24)-gap);
            assertEquals(width,ControlSizePolicy.spanWidth(width,gap,0,24));
            assertTrue(ControlSizePolicy.spanWidth(width,gap,8,4)>=0);
        }
    }

    @Test public void explicitAppSizesAreRemovedOnDeleteAndRetainedForActiveApps(){
        String app="app_0123456789abcdef01234567",other="app_1123456789abcdef01234567";
        assertEquals("wifi:3",ControlSizePolicy.retainSizes("wifi:3,"+app+":6",Collections.emptyList()));
        assertEquals("wifi:3,"+app+":6",ControlSizePolicy.retainSizes("wifi:3,"+app+":6",Arrays.asList(app)));
        assertEquals(app+":6",ControlSizePolicy.retainSizes(app+":6,"+other+":2,unknown:4",Arrays.asList(app)));
    }
}
