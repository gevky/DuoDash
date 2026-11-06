package dev.nova.deck;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class OutputNamePolicyTest {
    @Test public void suggestsRetroidAddonForNewRetroidOutput(){
        assertEquals("Retroid Addon",OutputNamePolicy.suggestion("Lontium Display Controller","DP Screen"));
        assertEquals("Retroid Addon",OutputNamePolicy.suggestion("","RDS External Display"));
    }

    @Test public void keepsDescriptiveDisplaySuggestionForOtherHardware(){
        assertEquals("Living Room TV",OutputNamePolicy.suggestion("HDMI","Living Room TV"));
        assertEquals("XREAL glasses",OutputNamePolicy.suggestion("Nreal Air","DP Screen"));
        assertEquals("External display",OutputNamePolicy.suggestion("","DP Screen"));
    }
}
