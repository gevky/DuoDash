package dev.nova.deck;

import org.junit.Test;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class WifiAddressMatcherTest {
    @Test public void acceptsSameIpv6AddressWithDifferentScopeIds() throws Exception {
        byte[] bytes = InetAddress.getByName("fe80::1234").getAddress();
        InetAddress local = Inet6Address.getByAddress(null, bytes, 1);
        InetAddress mdns = Inet6Address.getByAddress(null, bytes, 2);
        assertTrue(WifiAddressMatcher.isLocalWifiAddress(mdns, Arrays.asList(local)));
        byte[] otherBytes = InetAddress.getByName("fe80::1235").getAddress();
        InetAddress other = Inet6Address.getByAddress(null, otherBytes, 2);
        assertFalse(WifiAddressMatcher.isLocalWifiAddress(other, Arrays.asList(local)));
    }

    @Test public void acceptsMatchingIpv4AndRejectsNonLocalOrUnusableAddresses() throws Exception {
        InetAddress local = InetAddress.getByName("192.0.2.14");
        assertTrue(WifiAddressMatcher.isLocalWifiAddress(InetAddress.getByName("192.0.2.14"), Arrays.asList(local)));
        assertFalse(WifiAddressMatcher.isLocalWifiAddress(InetAddress.getByName("192.0.2.15"), Arrays.asList(local)));
        assertFalse(WifiAddressMatcher.isLocalWifiAddress(InetAddress.getByName("127.0.0.1"), Arrays.asList(local)));
        assertFalse(WifiAddressMatcher.isLocalWifiAddress(InetAddress.getByName("0.0.0.0"), Arrays.asList(local)));
    }
}
