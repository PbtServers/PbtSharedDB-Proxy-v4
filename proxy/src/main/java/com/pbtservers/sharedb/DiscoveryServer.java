package com.pbtservers.sharedb;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Servicio HTTPS explícito, autenticado y sin exportación de contraseñas. */
public final class DiscoveryServer implements AutoCloseable {
    private HttpsServer server;
    private ExecutorService executor;
    private record Access(DiscoveryProtocol.ConnectionInfo info,String token) {}
    public static DiscoveryServer open(Path root,ConfigurationManager.Config config,ProcessManager process) throws Exception {
        DiscoveryServer service=new DiscoveryServer();
        try { service.start(root,config,process);return service; }
        catch(Exception e) { service.close();throw e; }
    }
    private void start(Path root,ConfigurationManager.Config config,ProcessManager process) throws Exception {
        Path file=SafeFiles.child(root,"discovery.yml");
        if(!Files.exists(file)) { try(var in=getClass().getResourceAsStream("/discovery.yml")) { SafeFiles.write(file,new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8)); } }
        Map<?,?> settings=YamlFiles.read(file);
        if(!YamlFiles.flag(settings,"enabled"))return;
        String bind=YamlFiles.text(settings,"bind-host");int port=YamlFiles.port(settings,"port");
        if(!Set.of("127.0.0.1","0.0.0.0").contains(bind))throw new IOException("Bind de descubrimiento inválido");
        if(bind.equals("0.0.0.0") && (!YamlFiles.flag(settings,"network-isolation-confirmed") ||
           YamlFiles.port(settings,"allocated-port")!=port || !config.host().equals("0.0.0.0")))throw new IOException("Confirme aislamiento y asignaciones de SQL y descubrimiento");
        if(port==config.port())throw new IOException("Descubrimiento y SQL requieren puertos distintos");
        Map<String,Access> clients=new HashMap<>();
        if(!(settings.get("clients") instanceof List<?> entries))throw new IOException("clients debe ser una lista");
        for(Object entry:entries) {
            if(!(entry instanceof Map<?,?> map))throw new IOException("Cliente de descubrimiento inválido");
            String id=YamlFiles.text(map,"id");DiscoveryProtocol.identifier(id);
            String username=YamlFiles.text(map,"username"),host=YamlFiles.text(map,"user-host");
            var user=config.users().stream().filter(u->u.name().equals(username)&&u.host().equals(host)).findFirst().orElseThrow(()->new IOException("Cuenta SQL del cliente no declarada"));
            var info=new DiscoveryProtocol.ConnectionInfo(YamlFiles.text(settings,"advertised-sql-host"),config.port(),user.database(),user.name());
            String token=SafeFiles.secret(SafeFiles.child(root,"discovery-secrets"),id);
            if(clients.putIfAbsent(id,new Access(info,token))!=null)throw new IOException("ID de cliente duplicado");
        }
        if(clients.isEmpty())throw new IOException("Descubrimiento habilitado sin clientes autorizados");
        Path store=SafeFiles.child(root,YamlFiles.text(settings,"keystore"));
        Path passwordFile=SafeFiles.child(root,YamlFiles.text(settings,"keystore-password-file"));
        if(Files.isSymbolicLink(store) || Files.isSymbolicLink(passwordFile))throw new IOException("TLS no admite archivos enlazados");
        SafeFiles.restrict(store,false);SafeFiles.restrict(passwordFile,false);
        char[] password=Files.readString(passwordFile).trim().toCharArray();
        KeyStore keyStore=KeyStore.getInstance("PKCS12");
        try(var in=Files.newInputStream(store)) { keyStore.load(in,password); }
        KeyManagerFactory factory=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());factory.init(keyStore,password);Arrays.fill(password,'\0');
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(factory.getKeyManagers(),null,null);
        var replay=new DiscoveryProtocol.ReplayGuard();
        server=HttpsServer.create(new InetSocketAddress(bind,port),32);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),Thread.ofPlatform().name("pbtsharedb-descubrimiento-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);
        server.createContext(DiscoveryProtocol.PATH,exchange->{
            try {
                var headers=exchange.getRequestHeaders();
                String id=one(headers,"X-Pbt-Client"),ts=one(headers,"X-Pbt-Time"),nonce=one(headers,"X-Pbt-Nonce"),signature=one(headers,"X-Pbt-Signature");
                Access access=clients.get(id);
                if(!"GET".equals(exchange.getRequestMethod()) || !DiscoveryProtocol.PATH.equals(exchange.getRequestURI().getRawPath()) ||
                   exchange.getRequestURI().getRawQuery()!=null || access==null || ts==null || nonce==null ||
                   !DiscoveryProtocol.matches(DiscoveryProtocol.mac(access.token(),DiscoveryProtocol.request(id,ts,nonce)),signature) ||
                   !replay.accept(id,ts,nonce,Instant.now().getEpochSecond())) { exchange.sendResponseHeaders(401,-1);return; }
                if(process.state()!=ProcessManager.State.READY) { exchange.sendResponseHeaders(503,-1);return; }
                String body=DiscoveryProtocol.encode(access.info(),nonce);
                exchange.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control","no-store");
                exchange.getResponseHeaders().set("X-Pbt-Signature",DiscoveryProtocol.mac(access.token(),signature+"\n"+body));
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch(RuntimeException e) { exchange.close(); }
            finally { exchange.close(); }
        });
        server.start();
    }
    private static String one(Headers headers,String name) { var values=headers.get(name);return values!=null&&values.size()==1?values.getFirst():null; }
    @Override public void close() { if(server!=null)server.stop(0);if(executor!=null)executor.shutdownNow(); }
}
