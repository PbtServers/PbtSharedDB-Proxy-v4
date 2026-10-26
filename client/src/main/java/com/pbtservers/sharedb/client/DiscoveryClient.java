package com.pbtservers.sharedb.client;

import com.pbtservers.sharedb.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/** Independiente del ciclo de Paper para permitir pruebas reales de TLS/SQL. */
public final class DiscoveryClient {
    public record Bootstrap(URI endpoint,String clientId,String fingerprint,Path token,Path sqlPassword) {
        public Bootstrap {
            if(endpoint==null || !"https".equals(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getUserInfo()!=null ||
               endpoint.getQuery()!=null || endpoint.getFragment()!=null || !DiscoveryProtocol.PATH.equals(endpoint.getPath()))
                throw new IllegalArgumentException("Bootstrap exige HTTPS y la ruta /v1/discovery, sin credenciales ni parámetros");
            DiscoveryProtocol.identifier(clientId);
            if(fingerprint==null || !fingerprint.matches("[A-Fa-f0-9]{64}"))throw new IllegalArgumentException("Huella TLS inválida");
        }
        public static Bootstrap load(Path root) throws IOException {
            try {
                Map<?,?> settings=YamlFiles.read(SafeFiles.child(root,"config.yml"));
                if(!(settings.get("config-version") instanceof Number n) || n.intValue()!=1 || n.doubleValue()!=1)throw new IOException("Versión de configuración no compatible");
                if(!YamlFiles.flag(settings,"enabled"))throw new IOException("Cliente deshabilitado");
                Object password=settings.get("sql-password-file");
                if(!(password instanceof String value))throw new IOException("sql-password-file debe ser texto");
                return new Bootstrap(URI.create(YamlFiles.text(settings,"bootstrap-url")),YamlFiles.text(settings,"client-id"),
                    YamlFiles.text(settings,"certificate-sha256"),SafeFiles.child(root,YamlFiles.text(settings,"token-file")),
                    value.isBlank()?null:SafeFiles.child(root,value));
            }catch(IllegalArgumentException e) { throw new IOException("Bootstrap inválido; revise la configuración privada",e); }
        }
    }
    public record Result(DiscoveryProtocol.ConnectionInfo info,boolean sqlAuthenticated) {}
    public Result discover(Bootstrap bootstrap) throws Exception {
        String key=DiscoveryProtocol.readKey(bootstrap.token());
        String nonce=DiscoveryProtocol.nonce(),timestamp=Long.toString(Instant.now().getEpochSecond());
        String signature=DiscoveryProtocol.mac(key,DiscoveryProtocol.request(bootstrap.clientId(),timestamp,nonce));
        HttpsURLConnection connection=(HttpsURLConnection)bootstrap.endpoint().toURL().openConnection();
        connection.setSSLSocketFactory(PinnedTls.client(bootstrap.fingerprint()).getSocketFactory());
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
        connection.setRequestProperty("X-Pbt-Client",bootstrap.clientId());connection.setRequestProperty("X-Pbt-Time",timestamp);
        connection.setRequestProperty("X-Pbt-Nonce",nonce);connection.setRequestProperty("X-Pbt-Signature",signature);
        String body,responseSignature;
        try {
            if(connection.getResponseCode()!=200)throw new IOException("Descubrimiento rechazado o proxy no READY");
            try(var input=connection.getInputStream()) {
                byte[] bytes=input.readNBytes(DiscoveryProtocol.MAX_BODY+1);
                if(bytes.length>DiscoveryProtocol.MAX_BODY)throw new IOException("Respuesta de descubrimiento demasiado grande");
                body=new String(bytes,StandardCharsets.UTF_8);
            }
            responseSignature=connection.getHeaderField("X-Pbt-Signature");
        } finally { connection.disconnect(); }
        if(!DiscoveryProtocol.matches(DiscoveryProtocol.mac(key,signature+"\n"+body),responseSignature))
            throw new IOException("Firma de respuesta inválida");
        var info=DiscoveryProtocol.decode(body,nonce);
        probeGreeting(info);
        boolean authenticated=false;
        if(bootstrap.sqlPassword()!=null) {
            String password=DiscoveryProtocol.readKey(bootstrap.sqlPassword());
            Properties props=new Properties();props.setProperty("user",info.username());props.setProperty("password",password);
            props.setProperty("connectTimeout","3000");props.setProperty("socketTimeout","3000");
            try(var sqlConnection=new org.mariadb.jdbc.Driver().connect(info.jdbcUrl(),props);
                var statement=sqlConnection.prepareStatement("SELECT 1");var result=statement.executeQuery()) {
                if(!result.next() || result.getInt(1)!=1)throw new IOException("No se completó SELECT 1 autenticado");
                authenticated=true;
            } catch(java.sql.SQLException e) { throw new IOException("Autenticación SQL del cliente rechazada"); }
        }
        return new Result(info,authenticated);
    }
    public static void probeGreeting(DiscoveryProtocol.ConnectionInfo info) throws IOException {
        try(Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress(info.host(),info.port()),3000);socket.setSoTimeout(3000);
            var input=socket.getInputStream();byte[] header=input.readNBytes(4);
            if(header.length!=4)throw new IOException("Sin saludo SQL");
            int length=(header[0]&255)|((header[1]&255)<<8)|((header[2]&255)<<16);
            if(length<2 || length>4096)throw new IOException("Saludo SQL inválido");
            byte[] payload=input.readNBytes(length);
            if(payload.length!=length || payload[0]!=10 || !new String(payload,StandardCharsets.ISO_8859_1).contains("MariaDB"))
                throw new IOException("El endpoint no respondió como MariaDB");
        }
    }
    /** Solo escribe bajo el directorio propio; nunca exporta la clave SQL/token. */
    public Path writeConnection(Path root,String id,Result result) throws IOException {
        DiscoveryProtocol.identifier(id);
        Path directory=SafeFiles.child(root,"connections");SafeFiles.directory(directory);
        var info=result.info();
        String text="# Configuración manual. No modifica LuckPerms ni otros plugins.\n"+
            "host: '"+info.host()+"'\nport: "+info.port()+"\ndatabase: '"+info.database()+"'\nusername: '"+info.username()+"'\n"+
            "jdbc-url: '"+info.jdbcUrl()+"'\nverification: "+(result.sqlAuthenticated()?"authenticated-select-1":"mariadb-greeting-only")+
            "\nverified-at: '"+Instant.now()+"'\n# Contraseña omitida: aprovisionar manualmente por canal privado.\n";
        Path output=SafeFiles.child(root,"connections/"+id+".yml");SafeFiles.write(output,text);return output;
    }
}
