package dev.nova.deck.bridge;

/** Separates a missing audio API from a transient playback-status query failure. */
final class AudioStatusRecovery {
    private final boolean apiAvailable;
    private boolean queryHealthy=true;

    AudioStatusRecovery(boolean apiAvailable){this.apiAvailable=apiAvailable;}
    void recordQuery(boolean healthy){queryHealthy=healthy;}
    boolean available(){return apiAvailable&&queryHealthy;}
}
