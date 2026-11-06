package dev.nova.deck.bridge;

import static org.junit.Assert.*;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class LogicalCanvasTest {
    @Test public void offersLogicalSizesInsideNativeBoundsWithoutChangingNativeChoice(){
        int nativeWidth=2560,nativeHeight=1600;
        Map<String,LogicalCanvas> choices=LogicalCanvas.choices(nativeWidth,nativeHeight);

        assertEquals(nativeWidth,choices.get("native").width);
        assertEquals(nativeHeight,choices.get("native").height);
        assertEquals(1920,choices.get("reduced").width);
        assertEquals(1200,choices.get("reduced").height);
        assertEquals(1600,choices.get("ratio_4_3").height);
        assertTrue(Math.abs(choices.get("ratio_4_3").width*3-choices.get("ratio_4_3").height*4)<=3);
        assertTrue(Math.abs(choices.get("ratio_16_9").width*9-choices.get("ratio_16_9").height*16)<=15);

        Set<String> dimensions=new HashSet<>();
        for(LogicalCanvas choice:choices.values()){
            assertTrue(choice.width>0&&choice.height>0);
            assertTrue(choice.width<=nativeWidth&&choice.height<=nativeHeight);
            assertTrue("Duplicate logical size: "+choice.width+"x"+choice.height,dimensions.add(choice.width+"x"+choice.height));
            String label=choice.label.toLowerCase(java.util.Locale.ROOT);
            assertFalse(label.contains("hz"));assertFalse(label.contains("refresh"));
        }
    }

    @Test public void convertsCurrentLogicalOrientationToWmNaturalOrientationOnce(){
        assertArrayEquals(new int[]{720,1280},LogicalCanvas.toNaturalSize(1280,720,1));
        assertArrayEquals(new int[]{1080,1440},LogicalCanvas.toNaturalSize(1440,1080,1));
        assertArrayEquals(new int[]{1440,1080},LogicalCanvas.toNaturalSize(1440,1080,0));
    }
}
