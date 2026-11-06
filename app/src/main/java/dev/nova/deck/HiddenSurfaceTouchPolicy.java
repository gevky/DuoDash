package dev.nova.deck;

import java.util.HashSet;
import java.util.Set;

/** Prevents an external touchscreen from activating a DuoDash surface hidden on that output. */
final class HiddenSurfaceTouchPolicy {
    enum Phase { DOWN, CONTINUE, UP, CANCEL }

    private HiddenSurfaceTouchPolicy() {}

    static boolean isExternalTouchscreen(boolean touchscreenSource, boolean externalDevice) {
        return touchscreenSource && externalDevice;
    }

    static boolean shouldConsume(boolean externalTouchscreen, boolean externalConnected,
                                 boolean visibleOnExternalOutput) {
        return externalTouchscreen && externalConnected && !visibleOnExternalOutput;
    }

    /** Tracks blocked streams by input device so concurrent internal touches cannot release them. */
    static final class Gesture {
        private final Set<Integer> blockedDeviceIds=new HashSet<>();

        boolean shouldConsume(int deviceId,Phase phase,boolean externalTouchscreen,
                              boolean externalConnected,boolean visibleOnExternalOutput) {
            if(phase==Phase.DOWN){
                blockedDeviceIds.remove(deviceId);
                if(HiddenSurfaceTouchPolicy.shouldConsume(externalTouchscreen,externalConnected,visibleOnExternalOutput))
                    blockedDeviceIds.add(deviceId);
            }
            boolean blocked=blockedDeviceIds.contains(deviceId);
            if(phase==Phase.UP||phase==Phase.CANCEL)blockedDeviceIds.remove(deviceId);
            return blocked;
        }
    }
}
