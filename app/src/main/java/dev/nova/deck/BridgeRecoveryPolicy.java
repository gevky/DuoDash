package dev.nova.deck;

/** Pure decisions for avoiding unnecessary Wireless debugging work during recovery. */
final class BridgeRecoveryPolicy {
    private BridgeRecoveryPolicy() {}

    interface RecoveryOperations {
        void enableWirelessDebugging() throws Exception;
        String connectAndStart() throws Exception;
        void disconnectAdb() throws Exception;
        void restoreWirelessDebugging(int value) throws Exception;
        int readWirelessDebugging() throws Exception;
        boolean helperHealthy() throws Exception;
    }

    static void validateTemporaryEnable(int originalValue,boolean canWriteSettings,boolean localNetworkAvailable){
        if(originalValue!=0)return;
        if(!canWriteSettings)
            throw new SecurityException("Wireless debugging is off. Pair DuoDash while it is on to grant restore permission.");
        if(!localNetworkAvailable)
            throw new IllegalStateException("Waiting for Wi-Fi before restoring Wireless debugging.");
    }

    /**
     * Starts a helper with Wireless debugging temporarily enabled only when this operation owns
     * that change. Every owned change is restored after the ADB connection is closed, including
     * failed or interrupted startup attempts.
     */
    static String runWithTemporaryDebugging(int originalValue, boolean canWriteSettings,
                                            boolean localNetworkAvailable,
                                            RecoveryOperations operations) throws Exception {
        boolean enabledAtEntry=originalValue!=0;
        boolean enabledByThisOperation=!enabledAtEntry;
        validateTemporaryEnable(originalValue,canWriteSettings,localNetworkAvailable);

        String result=null;
        Exception operationFailure=null;
        try {
            if(enabledByThisOperation)operations.enableWirelessDebugging();
            result=operations.connectAndStart();
        } catch(Exception error) {
            operationFailure=error;
        }

        Exception cleanupFailure=null;
        try { operations.disconnectAdb(); }
        catch(Exception error) { cleanupFailure=error; }

        if(enabledByThisOperation) {
            try {
                operations.restoreWirelessDebugging(originalValue);
                int restored=operations.readWirelessDebugging();
                if(restored!=originalValue)throw new IllegalStateException("Wireless debugging did not return to its original state.");
                boolean helperHealthy=operations.helperHealthy();
                if(operationFailure==null&&!helperHealthy)
                    throw new IllegalStateException("The helper stopped responding after Wireless debugging was restored.");
            } catch(Exception error) {
                if(cleanupFailure==null)cleanupFailure=error;
                else cleanupFailure.addSuppressed(error);
            }
        }

        // Setup usually starts with Wireless debugging already on. Verify that the
        // helper survives closing that ADB session too, without changing the setting.
        if(!enabledByThisOperation&&operationFailure==null&&cleanupFailure==null){
            try{
                if(!operations.helperHealthy())throw new IllegalStateException("The helper stopped responding after the setup connection closed.");
            }catch(Exception error){cleanupFailure=error;}
        }

        if(cleanupFailure!=null) {
            IllegalStateException failure=new IllegalStateException(
                "DuoDash could not restore or verify Wireless debugging cleanup; system controls were not confirmed.",cleanupFailure);
            if(operationFailure!=null)failure.addSuppressed(operationFailure);
            throw failure;
        }
        if(operationFailure!=null)throw operationFailure;
        return result;
    }
}
