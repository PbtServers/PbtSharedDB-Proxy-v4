package com.pbtservers.sharedb;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class DatabaseManager {
    private final Path root;
    private final RuntimeManager.Runtime runtime;
    private final Path socket;
    public DatabaseManager(Path root, RuntimeManager.Runtime runtime, Path socket) { this.root=root;this.runtime=runtime;this.socket=socket; }
    public static String identifier(String value) {
        if (value==null || !value.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")) throw new IllegalArgumentException("Identificador SQL inválido");
        return "`"+value.replace("`","``")+"`";
    }
    public static void authorizedHost(String host) {
        if (host==null || !host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) throw new IllegalArgumentException("Se exige IPv4 explícita para cada host SQL autorizado");
        for (String part:host.split("\\.")) if (Integer.parseInt(part)>255 || (part.length()>1&&part.startsWith("0"))) throw new IllegalArgumentException("IPv4 inválida");
        if (host.equals("0.0.0.0") || host.equals("255.255.255.255")) throw new IllegalArgumentException("Host no autorizable");
    }
    List<String> clientArguments() {
        return new ArrayList<>(List.of(runtime.client().toString(),"--no-defaults","--protocol=socket","--socket="+socket,"--user="+runtime.osUser(),"--connect-timeout=2","--batch","--skip-column-names"));
    }
    String sql(String sql,int timeout) throws IOException,InterruptedException {
        Path out=Files.createTempFile(root,"sql-",".tmp"),err=Files.createTempFile(root,"sql-error-",".tmp");
        SafeFiles.restrict(out,false);SafeFiles.restrict(err,false);
        try { NativeCommand.run(clientArguments(),sql,out,err,timeout);return Files.readString(out).trim(); }
        finally { Files.deleteIfExists(out); Files.deleteIfExists(err); }
    }
    boolean ready() throws IOException,InterruptedException { return sql("SELECT 1;\n",3).equals("1"); }
    void secureAndProvision(ConfigurationManager.Config config) throws IOException,InterruptedException {
        // La cuenta administrativa se autentica por UID del socket, jamás por contraseña TCP.
        String users=sql("SELECT CONCAT(User,'@',Host) FROM mysql.user ORDER BY User,Host;\n",10);
        Set<String> allowed=new HashSet<>(Set.of("root@localhost",runtime.osUser()+"@localhost","mariadb.sys@localhost","pbt_health@127.0.0.1"));
        for (ConfigurationManager.User u:config.users()) {
            if (u.name().equals(runtime.osUser())) throw new IOException("El usuario SQL no puede coincidir con el usuario del contenedor");
            allowed.add(u.name()+"@"+u.host());
        }
        for (String account:users.split("\\R")) if (!allowed.contains(account)) throw new IOException("Cuenta SQL no administrada detectada; revise mysql.user localmente antes de continuar");
        sql("ALTER USER 'root'@'localhost' IDENTIFIED VIA unix_socket;\nALTER USER '"+runtime.osUser()+"'@'localhost' IDENTIFIED VIA unix_socket;\n",10);
        String health=SafeFiles.secret(SafeFiles.child(root,"secrets"),"health");
        sql("CREATE USER IF NOT EXISTS 'pbt_health'@'127.0.0.1' IDENTIFIED BY '"+health+"';\nALTER USER 'pbt_health'@'127.0.0.1' IDENTIFIED BY '"+health+"';\nREVOKE ALL PRIVILEGES, GRANT OPTION FROM 'pbt_health'@'127.0.0.1';\n",10);
        for (String db:config.databases()) sql("CREATE DATABASE IF NOT EXISTS "+identifier(db)+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n",10);
        for (ConfigurationManager.User user:config.users()) {
            String password=SafeFiles.secret(SafeFiles.child(root,"secrets"),user.name()+"-"+user.host());
            // Los valores DDL no admiten parámetros en todas las herramientas/variantes del servidor.
            // Solo se interpolan tokens previamente limitados a alfabetos sin comillas ni escapes.
            String account="'"+user.name()+"'@'"+user.host()+"'";
            sql("CREATE USER IF NOT EXISTS "+account+" IDENTIFIED BY '"+password+"';\nALTER USER "+account+" IDENTIFIED BY '"+password+"';\nREVOKE ALL PRIVILEGES, GRANT OPTION FROM "+account+";\nGRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP ON "+identifier(user.database())+".* TO "+account+";\n",15);
        }
    }
    boolean tcpReady(int port) throws Exception {
        Properties properties=new Properties();properties.setProperty("user","pbt_health");
        properties.setProperty("password",SafeFiles.secret(SafeFiles.child(root,"secrets"),"health"));
        properties.setProperty("connectTimeout","1500");properties.setProperty("socketTimeout","1500");
        try (var connection=new org.mariadb.jdbc.Driver().connect("jdbc:mariadb://127.0.0.1:"+port+"/",properties);
             var statement=connection.prepareStatement("SELECT 1");var result=statement.executeQuery()) {
            return result.next() && result.getInt(1)==1;
        }
    }
    void shutdown() throws IOException,InterruptedException { sql("SHUTDOWN;\n",5); }
}
