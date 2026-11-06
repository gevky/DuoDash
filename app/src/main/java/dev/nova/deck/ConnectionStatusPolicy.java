package dev.nova.deck;

/** Resolves the temporary success copy once the controller reports a live helper. */
final class ConnectionStatusPolicy {
    static final String RESTORE_VERIFYING="Controls restored; verifying connection…";

    private ConnectionStatusPolicy() { }

    static String afterBridgeStatus(String message,boolean bridgeReady){
        return bridgeReady&&RESTORE_VERIFYING.equals(message)?"":message;
    }
}
