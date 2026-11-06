package dev.nova.deck;

/** Bounded retry schedule for Wireless debugging becoming available after boot. */
final class BootReconnectPolicy {
    static final int MAX_BOOT_ATTEMPTS=6;
    static final int MAX_PAIR_HELPER_RECOVERY_ATTEMPTS=2;
    private BootReconnectPolicy(){ }
    static int attempts(boolean bootRecovery){return bootRecovery?MAX_BOOT_ATTEMPTS:1;}
    static long delayBeforeAttempt(boolean bootRecovery,int attempt){
        if(!bootRecovery||attempt<=0)return 0;
        long[] delays={0,3_000L,5_000L,8_000L,10_000L,10_000L};
        return attempt<delays.length?delays[attempt]:10_000L;
    }
    static long delayBeforePairHelperRecovery(int attempt){
        return attempt<=0?0:2_500L;
    }
    static boolean shouldPublishFailureNotification(boolean bootRecovery){return !bootRecovery;}
}
