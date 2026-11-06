package dev.nova.deck;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collection;

/** Matches an mDNS result only when its address bytes belong to a local Wi-Fi interface. */
final class WifiAddressMatcher {
    private WifiAddressMatcher() {}

    static boolean isLocalWifiAddress(InetAddress candidate, Collection<InetAddress> wifiAddresses) {
        if (candidate == null || wifiAddresses == null || candidate.isLoopbackAddress() || candidate.isAnyLocalAddress()) return false;
        byte[] address = candidate.getAddress();
        for (InetAddress local : wifiAddresses) {
            if (local != null && !local.isLoopbackAddress() && !local.isAnyLocalAddress()
                    && Arrays.equals(address, local.getAddress())) return true;
        }
        return false;
    }
}
