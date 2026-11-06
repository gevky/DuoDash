package dev.nova.deck.bridge;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class SurfaceLayerSelectorTest {
    @Test public void prefersNewestBlastSurfaceViewWhenAvailable() {
        assertEquals("SurfaceView[com.game/.Play](BLAST)#22", SurfaceLayerSelector.select("com.game", Arrays.asList(
            "com.game/.Play#90",
            "SurfaceView[com.game/.Play](BLAST)#17",
            "SurfaceView[com.game/.Play](BLAST)#22",
            "SurfaceView[com.game/.Play](BLAST)#9"
        )));
    }

    @Test public void fallsBackToNewestExactPackageActivityWindow() {
        assertEquals("com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture#881",
            SurfaceLayerSelector.select("com.retroarch.aarch64", Arrays.asList(
                "ActivityRecordInputSink com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture#879",
                "com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture#875",
                "com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture#881",
                "dev.nova.deck/dev.nova.deck.OverlayService#1200",
                "com.retroarch.aarch64.evil/com.example/.Play#999",
                "SurfaceView[com.other/.Play](BLAST)#1000"
            )));
    }

    @Test public void returnsNullWhenPackageHasNoPresentationLayer() {
        assertNull(SurfaceLayerSelector.select("com.game", Arrays.asList(
            "ActivityRecordInputSink com.game/.Play#8",
            "SurfaceView[com.other/.Play](BLAST)#22"
        )));
    }
}
