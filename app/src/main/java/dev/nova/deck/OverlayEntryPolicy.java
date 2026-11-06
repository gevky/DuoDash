package dev.nova.deck;

/** Keeps a user-requested panel entry from being replaced by an internal window rebind. */
final class OverlayEntryPolicy {
    private OverlayEntryPolicy() {}

    static String merge(String pending,String incoming) {
        if(pending==null||pending.isEmpty())return incoming;
        boolean pendingUser=!internal(pending),incomingUser=!internal(incoming);
        if(pendingUser&&!incomingUser)return pending;
        if(incomingUser)return incoming;
        return "rebind".equals(pending)&&"background_rebind".equals(incoming)?pending:incoming;
    }

    private static boolean internal(String action) {
        return "rebind".equals(action)||"background_rebind".equals(action);
    }
}
