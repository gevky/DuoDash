package dev.nova.deck;

final class UsbDebuggingPolicy {
    private UsbDebuggingPolicy() {}

    interface Operations {
        int read() throws Exception;
        boolean enable() throws Exception;
    }

    static void ensureEnabled(boolean canWrite, Operations operations) throws Exception {
        if (operations.read() == 1) return;
        if (!canWrite) {
            throw new IllegalStateException("Turn on USB debugging in Developer options to keep controls working with Wi-Fi off.");
        }
        if (!operations.enable() || operations.read() != 1) {
            throw new IllegalStateException("Android could not enable USB debugging. Turn it on in Developer options.");
        }
    }
}
