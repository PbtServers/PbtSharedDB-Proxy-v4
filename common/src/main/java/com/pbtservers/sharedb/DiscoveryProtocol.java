package com.pbtservers.sharedb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Protocolo versionado común, sin dependencia de Velocity o Paper. */
public final class DiscoveryProtocol {
    public static final String PATH="/v1/discovery";
    public static final int MAX_BODY=4096;
    private DiscoveryProtocol() {}
    public record ConnectionInfo(String host,int port,String database,String username) {
        public ConnectionInfo {
            if(host==null || !host.matches("[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}") || port<1024 || port>65535)
                throw new IllegalArgumentException("Endpoint SQL inválido");
            if(database==null || !database.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))throw new IllegalArgumentException("Base inválida");
            identifier(username);
            if(Set.of("root","mysql","mariadb.sys","pbt_health").contains(username.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Cuenta administrativa no exportable");
        }
        public String jdbcUrl() { return "jdbc:mariadb://"+host+":"+port+"/"+database; }
    }
    public static void identifier(String id) { if(id==null || !id.matches("[A-Za-z][A-Za-z0-9_]{0,31}"))throw new IllegalArgumentException("Identificador de descubrimiento inválido"); }
    public static String readKey(Path file) throws IOException {
        if(Files.isSymbolicLink(file) || !Files.isRegularFile(file))throw new IOException("Falta el archivo privado de clave");
        SafeFiles.restrict(file,false);
        String key=Files.readString(file).trim();
        if(!key.matches("[A-Za-z0-9_-]{43}"))throw new IOException("Clave privada inválida");
        return key;
    }
    public static String nonce() { byte[] bytes=new byte[16];new SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes); }
    public static String request(String id,String timestamp,String nonce) { return "GET\n"+PATH+"\n"+id+"\n"+timestamp+"\n"+nonce; }
    public static String mac(String key,String content) {
        try { Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(Base64.getUrlDecoder().decode(key),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8))); }
        catch(GeneralSecurityException e) { throw new IllegalStateException("HMAC no disponible",e); }
    }
    public static boolean matches(String expected,String actual) {
        if(actual==null || !actual.matches("[a-f0-9]{64}"))return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),actual.getBytes(StandardCharsets.US_ASCII));
    }
    public static String encode(ConnectionInfo info,String nonce) {
        return "version=1\nhost="+info.host()+"\nport="+info.port()+"\ndatabase="+info.database()+"\nusername="+info.username()+"\nnonce="+nonce+"\n";
    }
    public static ConnectionInfo decode(String body,String nonce) throws IOException {
        if(body.length()>MAX_BODY)throw new IOException("Respuesta demasiado grande");
        Map<String,String> values=new HashMap<>();
        for(String line:body.split("\n")) {
            String[] parts=line.split("=",2);
            if(parts.length!=2 || values.putIfAbsent(parts[0],parts[1])!=null)throw new IOException("Respuesta malformada o duplicada");
        }
        if(!values.keySet().equals(Set.of("version","host","port","database","username","nonce")) ||
           !"1".equals(values.get("version")) || !nonce.equals(values.get("nonce")))throw new IOException("Versión o nonce de respuesta inválido");
        try { return new ConnectionInfo(values.get("host"),Integer.parseInt(values.get("port")),values.get("database"),values.get("username")); }
        catch(IllegalArgumentException e) { throw new IOException("Metadatos SQL inválidos",e); }
    }
    /** Caché acotada: un token válido no permite reusar ni agotar memoria con nonces. */
    public static final class ReplayGuard {
        private final Map<String,Long> seen=new HashMap<>();
        public synchronized boolean accept(String id,String timestamp,String nonce,long now) {
            if(nonce==null || !nonce.matches("[a-f0-9]{32}") || timestamp==null || !timestamp.matches("[0-9]{10,12}"))return false;
            long instant;
            try { instant=Long.parseLong(timestamp); }catch(NumberFormatException e) { return false; }
            if(instant<now-30 || instant>now+30)return false;
            seen.entrySet().removeIf(e->e.getValue()<now-60);
            if(seen.size()>=4096)return false;
            return seen.putIfAbsent(id+":"+nonce,now)==null;
        }
    }
}
