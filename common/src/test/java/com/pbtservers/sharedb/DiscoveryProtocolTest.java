package com.pbtservers.sharedb;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryProtocolTest {
    final String key=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    @Test void signaturesBindClientTimeAndNonce() {
        String request=DiscoveryProtocol.request("lobby","1793000000","a".repeat(32));
        String signature=DiscoveryProtocol.mac(key,request);
        assertTrue(DiscoveryProtocol.matches(signature,signature));
        assertFalse(DiscoveryProtocol.matches(signature,DiscoveryProtocol.mac(key,request+"x")));
        assertFalse(DiscoveryProtocol.matches(signature,null));assertFalse(DiscoveryProtocol.matches(signature,"bad"));
    }
    @Test void replayExpiryAndFutureAreRejected() {
        var guard=new DiscoveryProtocol.ReplayGuard();long now=1793000000L;
        assertTrue(guard.accept("lobby","1793000000","a".repeat(32),now));
        assertFalse(guard.accept("lobby","1793000000","a".repeat(32),now));
        assertFalse(guard.accept("lobby","1792999900","b".repeat(32),now));
        assertFalse(guard.accept("lobby","1793000100","c".repeat(32),now));
        assertFalse(guard.accept("lobby","9223372036854775807","c".repeat(32),now));
    }
    @Test void replayCacheIsBounded() {
        var guard=new DiscoveryProtocol.ReplayGuard();long now=1793000000L;
        for(int i=0;i<4096;i++)assertTrue(guard.accept("lobby",Long.toString(now),String.format("%032x",i),now));
        assertFalse(guard.accept("lobby",Long.toString(now),"f".repeat(32),now));
        assertTrue(guard.accept("lobby",Long.toString(now+61),"f".repeat(32),now+61));
    }
    @Test void metadataNeverAllowsPasswordsAdministrativeAccountsOrDuplicates() throws Exception {
        var info=new DiscoveryProtocol.ConnectionInfo("proxy.internal",3306,"luckperms","lp_backend");
        String body=DiscoveryProtocol.encode(info,"a".repeat(32));
        assertEquals(info,DiscoveryProtocol.decode(body,"a".repeat(32)));
        assertThrows(IOException.class,()->DiscoveryProtocol.decode(body+"password=secret\n","a".repeat(32)));
        assertThrows(IOException.class,()->DiscoveryProtocol.decode(body+"port=1234\n","a".repeat(32)));
        assertThrows(IOException.class,()->DiscoveryProtocol.decode(body,"b".repeat(32)));
        assertThrows(IllegalArgumentException.class,()->new DiscoveryProtocol.ConnectionInfo("proxy",3306,"luckperms","root"));
        assertThrows(IllegalArgumentException.class,()->new DiscoveryProtocol.ConnectionInfo("proxy\npassword=x",3306,"luckperms","app"));
    }
}
