package com.pbtservers.sharedb.client;

import com.pbtservers.sharedb.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class DiscoveryIntegrationTest {
    @TempDir Path root;
    ProcessManager process;
    DiscoveryServer server;
    @AfterEach void stop() throws Exception { if(server!=null)server.close();if(process!=null)process.stop(); }
    private int freePort() throws IOException { try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    @Test void realTlsHmacReplayAndAuthenticatedSqlWithLeastPrivilegeAccount() throws Exception {
        assertEquals("Linux",System.getProperty("os.name"),"Se exige Linux/MariaDB real");
        Path db=root.resolve("db");SafeFiles.directory(db);
        int sqlPort=freePort();
        String template;
        try(var input=getClass().getResourceAsStream("/proxy-fixture.yml")) { template=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8); }
        SafeFiles.write(db.resolve("config.yml"),template.replace("enabled: false","enabled: true").replace("port: 3306","port: "+sqlPort)
            .replace("databases: []","databases: [appdb]").replace("\nusers: []","\nusers:\n  - name: app_test\n    host: 127.0.0.1\n    database: appdb"));
        var config=new ConfigurationManager(db).load();process=new ProcessManager(db);process.start(config);
        String tlsPassword=SafeFiles.secret(db,"tls-password");
        Path store=db.resolve("tls.p12");
        Path keytool=Path.of(System.getProperty("java.home"),"bin","keytool");
        var generation=new ProcessBuilder(keytool.toString(),"-genkeypair","-alias","discovery","-keyalg","RSA","-keysize","2048",
            "-validity","30","-dname","CN=localhost","-ext","SAN=DNS:localhost,IP:127.0.0.1","-storetype","PKCS12",
            "-keystore",store.toString(),"-storepass:file",db.resolve("tls-password.key").toString())
            .redirectErrorStream(true).redirectOutput(root.resolve("keytool.log").toFile()).start();
        if(!generation.waitFor(45,TimeUnit.SECONDS)) { generation.destroyForcibly();fail("keytool agotó su plazo"); }
        assertEquals(0,generation.exitValue(),"No se generó el certificado de prueba");
        KeyStore keys=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(store)) { keys.load(in,tlsPassword.toCharArray()); }
        String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keys.getCertificate("discovery").getEncoded()));
        int port=freePort();
        SafeFiles.write(db.resolve("discovery.yml"),"enabled: true\nbind-host: 127.0.0.1\nport: "+port+"\nadvertised-sql-host: 127.0.0.1\nkeystore: tls.p12\nkeystore-password-file: tls-password.key\nclients:\n  - id: lobby\n    username: app_test\n    user-host: 127.0.0.1\n");
        server=DiscoveryServer.open(db,config,process);
        Path clientRoot=root.resolve("client");SafeFiles.directory(clientRoot);
        String token=DiscoveryProtocol.readKey(db.resolve("discovery-secrets/lobby.key"));
        SafeFiles.write(clientRoot.resolve("token.key"),token);
        String sqlPassword=DiscoveryProtocol.readKey(db.resolve("secrets/app_test-127.0.0.1.key"));
        SafeFiles.write(clientRoot.resolve("sql.key"),sqlPassword);
        URI endpoint=URI.create("https://127.0.0.1:"+port+DiscoveryProtocol.PATH);
        var bootstrap=new DiscoveryClient.Bootstrap(endpoint,"lobby",fingerprint,clientRoot.resolve("token.key"),clientRoot.resolve("sql.key"));
        var client=new DiscoveryClient();var result=client.discover(bootstrap);
        assertTrue(result.sqlAuthenticated());assertEquals("app_test",result.info().username());
        String output=Files.readString(client.writeConnection(clientRoot,"lobby",result));
        assertFalse(output.contains(token));assertFalse(output.contains(sqlPassword));assertFalse(output.contains("password:"));
        var greetingOnly=client.discover(new DiscoveryClient.Bootstrap(endpoint,"lobby",fingerprint,clientRoot.resolve("token.key"),null));
        assertFalse(greetingOnly.sqlAuthenticated());
        HttpClient http=HttpClient.newBuilder().sslContext(PinnedTls.client(fingerprint)).build();
        String ts=Long.toString(Instant.now().getEpochSecond()),nonce=DiscoveryProtocol.nonce();
        var request=HttpRequest.newBuilder(endpoint).header("X-Pbt-Client","lobby").header("X-Pbt-Time",ts).header("X-Pbt-Nonce",nonce)
            .header("X-Pbt-Signature",DiscoveryProtocol.mac(token,DiscoveryProtocol.request("lobby",ts,nonce))).GET().build();
        assertEquals(200,http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(401,http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode(),"Repetición no rechazada");
        SafeFiles.write(clientRoot.resolve("wrong.key"),Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
        assertThrows(Exception.class,()->client.discover(new DiscoveryClient.Bootstrap(endpoint,"lobby",fingerprint,clientRoot.resolve("wrong.key"),null)));
        assertThrows(Exception.class,()->client.discover(new DiscoveryClient.Bootstrap(endpoint,"lobby","0".repeat(64),clientRoot.resolve("token.key"),null)));
        assertThrows(IOException.class,()->client.discover(new DiscoveryClient.Bootstrap(endpoint,"lobby",fingerprint,clientRoot.resolve("token.key"),clientRoot.resolve("wrong.key"))));
        process.stop();assertThrows(IOException.class,()->client.discover(bootstrap),"Un proxy sin SQL READY no debe emitir metadatos");
    }
}
