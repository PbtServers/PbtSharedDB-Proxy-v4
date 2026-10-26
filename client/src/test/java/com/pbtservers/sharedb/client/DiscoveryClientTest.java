package com.pbtservers.sharedb.client;
import com.pbtservers.sharedb.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryClientTest {
    @TempDir Path root;
    @Test void bootstrapRejectsPlaintextUserInfoAndQuery() {
        for(String url:new String[]{"http://localhost:3307/v1/discovery","https://user:password@localhost/v1/discovery","https://localhost/v1/discovery?scan=1"})
            assertThrows(IllegalArgumentException.class,()->new DiscoveryClient.Bootstrap(URI.create(url),"lobby","a".repeat(64),root.resolve("token"),null));
    }
    @Test void outputStaysPrivateAndCannotModifyOtherPlugins() throws Exception {
        var client=new DiscoveryClient();
        var info=new DiscoveryProtocol.ConnectionInfo("proxy.internal",3306,"luckperms","lp_lobby");
        Path foreign=root.resolve("LuckPerms-config.yml");Files.writeString(foreign,"no modificar");
        Path output=client.writeConnection(root,"lobby",new DiscoveryClient.Result(info,true));
        String body=Files.readString(output);
        assertFalse(body.contains("password:"));assertFalse(body.contains("token:"));assertTrue(body.contains("authenticated-select-1"));
        assertEquals("no modificar",Files.readString(foreign));
        assertThrows(IllegalArgumentException.class,()->client.writeConnection(root,"../LuckPerms",new DiscoveryClient.Result(info,true)));
        if(Files.getFileStore(output).supportsFileAttributeView("posix"))assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(output)));
    }
    @Test void tcpPortAloneIsNotEnoughToAcceptSql() throws Exception {
        try(ServerSocket listener=new ServerSocket(0)) {
            Thread peer=Thread.ofVirtual().start(()->{try(var s=listener.accept()){s.getOutputStream().write(new byte[]{2,0,0,0,10,0});}catch(Exception ignored){}});
            assertThrows(java.io.IOException.class,()->DiscoveryClient.probeGreeting(new DiscoveryProtocol.ConnectionInfo("127.0.0.1",listener.getLocalPort(),"db","app")));
            peer.join(5000);
        }
    }
}
