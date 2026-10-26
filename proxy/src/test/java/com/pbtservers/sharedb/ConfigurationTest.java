package com.pbtservers.sharedb;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    @TempDir Path root;
    ConfigurationManager manager;
    @BeforeEach void setup() throws Exception { manager=new ConfigurationManager(root);manager.load(); }
    void replace(String from,String to) throws Exception { Path file=root.resolve("config.yml");Files.writeString(file,Files.readString(file).replace(from,to)); }
    @Test void defaultsAreDisabledAndLocal() throws Exception {
        var c=manager.load();assertFalse(c.enabled());assertFalse(c.autoStart());assertEquals("127.0.0.1",c.host());assertEquals(root.resolve("data"),c.data());
    }
    @Test void remoteRequiresNetworkConfirmationAndAllocation() throws Exception {
        replace("host: 127.0.0.1","host: 0.0.0.0");assertThrows(IOException.class,manager::load);
        replace("remote-access: false","remote-access: true");assertThrows(IOException.class,manager::load);
        replace("network-isolation-confirmed: false","network-isolation-confirmed: true");assertEquals("0.0.0.0",manager.load().host());
        replace("allocated-port: 3306","allocated-port: 3307");assertThrows(IOException.class,manager::load);
    }
    @Test void rejectsTraversalAndReservedPaths() throws Exception {
        replace("data-directory: data","data-directory: ../datos");assertThrows(IOException.class,manager::load);
        replace("data-directory: ../datos","data-directory: secrets");assertThrows(IOException.class,manager::load);
    }
    @Test void futureConfigurationIsPreserved() throws Exception {
        replace("config-version: 1","config-version: 2");String before=Files.readString(root.resolve("config.yml"));
        assertThrows(IOException.class,manager::load);assertEquals(before,Files.readString(root.resolve("config.yml")));
    }
    @Test void rejectsDuplicateKeysAndWrongTypes() throws Exception {
        Files.writeString(root.resolve("config.yml"),"enabled: true\n",StandardOpenOption.APPEND);assertThrows(IOException.class,manager::load);
    }
    @Test void validatesUsersAndDatabases() throws Exception {
        replace("databases: []","databases: [luckperms]");replace("\nusers: []","\nusers:\n  - name: lp_lobby\n    host: 172.18.0.12\n    database: luckperms");
        assertEquals(1,manager.load().users().size());replace("host: 172.18.0.12","host: '%'");assertThrows(IOException.class,manager::load);
    }
    @Test void secretsAreStableAndIndependent() throws Exception {
        Path secrets=root.resolve("secrets");String a=SafeFiles.secret(secrets,"a"),b=SafeFiles.secret(secrets,"b");
        assertEquals(43,a.length());assertNotEquals(a,b);assertEquals(a,SafeFiles.secret(secrets,"a"));
        Files.writeString(secrets.resolve("a.key"),"roto");assertThrows(IOException.class,()->SafeFiles.secret(secrets,"a"));
    }
}
