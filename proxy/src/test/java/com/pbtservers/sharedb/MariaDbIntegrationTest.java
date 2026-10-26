package com.pbtservers.sharedb;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class MariaDbIntegrationTest {
    @TempDir Path root;
    ConfigurationManager.Config config;
    ProcessManager process;
    Connection connect() throws Exception {
        Properties props=new Properties();props.setProperty("user","lp_test");
        props.setProperty("password",Files.readString(root.resolve("secrets/lp_test-127.0.0.1.key")).trim());
        props.setProperty("connectTimeout","2000");props.setProperty("socketTimeout","5000");
        return new org.mariadb.jdbc.Driver().connect("jdbc:mariadb://127.0.0.1:13316/luckperms",props);
    }
    @BeforeEach void configure() throws Exception {
        assertEquals("Linux",System.getProperty("os.name"),"integrationTest exige Linux y MariaDB real, no simulación");
        var configurations=new ConfigurationManager(root);configurations.load();Path file=root.resolve("config.yml");
        Files.writeString(file,Files.readString(file).replace("enabled: false","enabled: true").replace("port: 3306","port: 13316")
            .replace("databases: []","databases: [luckperms]").replace("\nusers: []","\nusers:\n  - name: lp_test\n    host: 127.0.0.1\n    database: luckperms"));
        if ("1".equals(System.getenv("PBT_REMOTE_PROBE"))) {
            Files.writeString(file,Files.readString(file).replace("\nhost: 127.0.0.1","\nhost: 0.0.0.0")
                .replace("remote-access: false","remote-access: true").replace("network-isolation-confirmed: false","network-isolation-confirmed: true")
                .replace("\nbackups:","\n  - name: lp_remote\n    host: 172.30.85.11\n    database: luckperms\nbackups:"));
        }
        config=configurations.load();process=new ProcessManager(root);
    }
    @AfterEach void cleanup() throws Exception { if(process!=null)process.stop(); }
    @Test void missingNativeToolFailsBeforeInitializingData() throws Exception {
        Path file=root.resolve("config.yml");
        Files.writeString(file,Files.readString(file).replace("binary-path: /usr/sbin/mariadbd","binary-path: /usr/sbin/mariadbd-ausente"));
        config=new ConfigurationManager(root).load();
        assertThrows(java.io.IOException.class,()->process.start(config));
        assertEquals(ProcessManager.State.FAILED,process.state());assertFalse(Files.exists(config.data()));
        assertTrue(process.info().contains("bibliotecas nativas"));
    }
    @Test void neverReinitializesAnExistingUnownedDirectory() throws Exception {
        Files.createDirectory(config.data());Path existing=config.data().resolve("archivo-existente");Files.writeString(existing,"conservar");
        assertThrows(java.io.IOException.class,()->process.start(config));assertEquals(ProcessManager.State.FAILED,process.state());
        assertEquals("conservar",Files.readString(existing));assertFalse(Files.exists(config.data().resolve("mysql")));
    }
    @Test void realInitializationPersistencePrivilegesBackupAndRestore() throws Exception {
        process.start(config);assertEquals(ProcessManager.State.READY,process.state());
        try (Connection c=connect();Statement s=c.createStatement()) {
            s.execute("CREATE TABLE dato (id INT PRIMARY KEY, texto VARCHAR(100)) ENGINE=InnoDB");
            try (PreparedStatement p=c.prepareStatement("INSERT INTO dato VALUES (?,?)")) { p.setInt(1,1);p.setString(2,"persistente ñ");p.executeUpdate(); }
            assertThrows(SQLException.class,()->s.executeQuery("SELECT * FROM mysql.global_priv"));
        }
        if ("1".equals(System.getenv("PBT_REMOTE_PROBE"))) remoteProbe();
        Properties rootProps=new Properties();rootProps.setProperty("user","root");rootProps.setProperty("connectTimeout","2000");
        assertThrows(SQLException.class,()->new org.mariadb.jdbc.Driver().connect("jdbc:mariadb://127.0.0.1:13316/",rootProps));
        Path backup=new BackupManager(root).backup(process,config);assertTrue(Files.size(backup)>0);
        String dump=Files.readString(backup);assertFalse(dump.contains(Files.readString(root.resolve("secrets/health.key")).trim()));
        String credential=Files.readString(root.resolve("secrets/lp_test-127.0.0.1.key"));
        String ownership=Files.readString(config.data().resolve(".pbtsharedb-owned"));
        process.stop();
        // Nueva instancia: no depender de estado Java conservado por restart().
        process=new ProcessManager(root);process.start(config);
        assertTrue(java.security.MessageDigest.isEqual(credential.getBytes(java.nio.charset.StandardCharsets.UTF_8),Files.readAllBytes(root.resolve("secrets/lp_test-127.0.0.1.key"))),"La clave debe conservarse tras reiniciar (valores privados omitidos)");
        assertEquals(ownership,Files.readString(config.data().resolve(".pbtsharedb-owned")));
        try (Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT texto FROM dato WHERE id=1")) { assertTrue(r.next());assertEquals("persistente ñ",r.getString(1)); }
        try (Connection c=connect();Statement s=c.createStatement()) { s.execute("DROP TABLE dato"); }
        // Restauración lógica real: el dump solo incluye las bases de aplicación.
        process.database().sql(Files.readString(backup),60);
        try (Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT texto FROM dato WHERE id=1")) { assertTrue(r.next());assertEquals("persistente ñ",r.getString(1));assertFalse(r.next()); }
        process.stop();process=new ProcessManager(root);process.start(config);
        try (Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT texto FROM dato WHERE id=1")) { assertTrue(r.next());assertEquals("persistente ñ",r.getString(1)); }
        ProcessManager duplicate=new ProcessManager(root);
        assertThrows(Exception.class,()->duplicate.start(config));assertEquals(ProcessManager.State.READY,process.state());
        // El intento competidor no puede detener el proceso propietario.
        assertTrue(process.database().ready());duplicate.stop();
    }
    private void remoteProbe() throws Exception {
        Path probe=Path.of("/work/build/remote-probe");SafeFiles.directory(probe);
        String password=Files.readString(root.resolve("secrets/lp_remote-172.30.85.11.key")).trim();
        SafeFiles.write(probe.resolve("client.cnf"),"[client]\nhost=pbtsharedb-integracion\nport=13316\nuser=lp_remote\npassword="+password+"\ndatabase=luckperms\n");
        SafeFiles.write(probe.resolve("ready"),"preparado\n");
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
        while (!Files.exists(probe.resolve("passed")) && System.nanoTime()<end) Thread.sleep(250);
        assertTrue(Files.exists(probe.resolve("passed")),"El segundo contenedor no confirmó la consulta SQL");
        assertTrue(Files.readString(probe.resolve("result")).contains("persistente"));
        Files.delete(probe.resolve("client.cnf"));
    }
}
