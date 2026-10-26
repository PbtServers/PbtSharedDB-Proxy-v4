package com.pbtservers.sharedb;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class ProcessManager implements AutoCloseable {
    public enum State { STOPPED, STARTING, READY, STOPPING, FAILED }
    private final Path root;
    private volatile State state=State.STOPPED;
    private volatile String diagnostic="sin iniciar";
    private volatile Process process;
    private FileChannel lockChannel;
    private FileLock lock;
    private static final Set<Path> LOCAL_LOCKS=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Path localLock;
    private volatile ConfigurationManager.Config config;
    private RuntimeManager.Runtime runtime;
    private DatabaseManager database;
    private Path socket;
    public ProcessManager(Path root) { this.root=root.toAbsolutePath().normalize(); }
    public State state() {
        Process current=process;
        if (state==State.READY && current!=null && !current.isAlive()) { diagnostic="fallo: mariadbd terminó después de READY";state=State.FAILED; }
        return state;
    }
    public synchronized void start(ConfigurationManager.Config settings) throws Exception {
        if (state()==State.READY) return;
        if (process!=null && process.isAlive()) throw new IOException("El proceso anterior aún está vivo; no se inicia otro");
        releaseLock();
        if (!settings.enabled()) throw new IOException("Plugin deshabilitado: revise enabled en config.yml");
        state=State.STARTING;config=settings;
        try {
            diagnostic="comprobando puerto SQL";
            checkPort(settings.host(),settings.port());
            diagnostic="comprobando binarios, arquitectura y bibliotecas nativas";
            runtime=new RuntimeManager(root).resolve(settings);
            diagnostic="comprobando permisos, escritura y bloqueo del volumen";
            Path data=SafeFiles.child(root,root.relativize(settings.data()).toString());
            SafeFiles.directory(data);
            Path canonicalData=data.toRealPath();
            probeWritable(data);
            if (!LOCAL_LOCKS.add(canonicalData)) throw new IOException("Datadir ya administrado en esta JVM");
            localLock=canonicalData;
            lockChannel=FileChannel.open(SafeFiles.child(data,".pbtsharedb.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            lock=lockChannel.tryLock();
            if (lock==null) throw new IOException("Datadir bloqueado por otra instancia");
            Path run=SafeFiles.child(root,"run"); SafeFiles.directory(run);
            probeWritable(run);
            socket=SafeFiles.child(run,"mariadb.sock");
            if (socket.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>100) throw new IOException("Ruta de socket demasiado larga; acorte la ruta del servidor");
            // No borrar sockets/PID heredados: pueden pertenecer a un proceso aún activo.
            if (Files.exists(socket,LinkOption.NOFOLLOW_LINKS) || Files.exists(run.resolve("mariadb.pid")))
                throw new IOException("Socket/PID existente: confirme que no queda mariadbd activo; consulte recuperación en docs/PTERODACTYL.md");
            Path marker=SafeFiles.child(data,".pbtsharedb-owned");
            diagnostic="validando propiedad e inicialización del datadir";
            if (!Files.exists(marker)) {
                try (var entries=Files.list(data)) {
                    if (entries.anyMatch(p->!p.getFileName().toString().equals(".pbtsharedb.lock"))) throw new IOException("Datadir existente sin marca de propiedad: no se inicializa ni modifica");
                }
                if (!settings.initialize()) throw new IOException("Datadir vacío y initialization.enabled desactivado");
                List<String> init=new ArrayList<>(List.of(runtime.installer().toString(),"--no-defaults","--datadir="+data,"--auth-root-authentication-method=socket","--auth-root-socket-user="+runtime.osUser(),"--skip-test-db"));
                if (runtime.basedir()!=null) init.add("--basedir="+runtime.basedir());
                Path log=SafeFiles.child(root,"initialization.log");SafeFiles.write(log,"");
                NativeCommand.run(init,null,log,log,settings.startupTimeout());
                SafeFiles.write(marker,RuntimeManager.VERSION+"\n");
            } else if (!Files.readString(marker).trim().equals(RuntimeManager.VERSION) || !Files.isDirectory(data.resolve("mysql"))) {
                throw new IOException("Datadir incompleto o versión diferente. No se actualiza ni reinicializa automáticamente");
            }
            database=new DatabaseManager(root,runtime,socket);
            diagnostic="aprovisionando cuentas por socket sin TCP";
            launch(false);
            database.secureAndProvision(settings);
            terminate(false);
            checkPort(settings.host(),settings.port());
            diagnostic="esperando SELECT 1 TCP autenticado";
            launch(true);
            state=State.READY;
            diagnostic="validaciones completadas; SQL autenticado disponible";
        } catch (Exception e) {
            diagnostic="fallo: "+diagnostic;
            try { terminate(true); } catch (Exception suppressed) { e.addSuppressed(suppressed); }
            state=State.FAILED;
            throw e;
        }
    }
    static void probeWritable(Path directory) throws IOException {
        Path probe=null;
        try {
            probe=Files.createTempFile(directory,"preflight-",".tmp");
            SafeFiles.write(probe,"comprobación de escritura\n");
        } catch (IOException e) { throw new IOException("No se puede escribir en el volumen; revise UID, permisos, espacio y montaje de solo lectura",e); }
        finally { if(probe!=null) Files.deleteIfExists(probe); }
    }
    static void checkPort(String host,int port) throws IOException {
        // Linux conserva conexiones cerradas en TIME_WAIT. MariaDB reutiliza esas
        // direcciones; la sonda debe hacer lo mismo para permitir un reinicio limpio.
        // SO_REUSEADDR no es SO_REUSEPORT: un listener activo sigue siendo rechazado.
        try (ServerSocket probe=new ServerSocket()) { probe.setReuseAddress(System.getProperty("os.name").equals("Linux"));probe.bind(new InetSocketAddress(host,port)); }
        catch (IOException e) { throw new IOException("Puerto SQL ocupado o no disponible: "+port,e); }
    }
    private void launch(boolean tcp) throws Exception {
        Path options=SafeFiles.child(root,"mariadb.cnf");Path run=SafeFiles.child(root,"run");
        Path fileIo=SafeFiles.child(root,"file-io");SafeFiles.directory(fileIo);
        Path log=SafeFiles.child(root,config.log());
        if (!Files.exists(log)) SafeFiles.write(log,"");
        String text="[mariadbd]\n"+
            "datadir=\""+config.data()+"\"\nsocket=\""+socket+"\"\npid-file=\""+run.resolve("mariadb.pid")+"\"\n"+
            "log-error=\""+log+"\"\n"+
            "bind-address="+config.host()+"\nport="+config.port()+"\nskip-name-resolve\nlocal-infile=0\n"+
            "max-connections=100\ninnodb-buffer-pool-size=128M\ninnodb-flush-log-at-trx-commit=1\n"+
            "general-log=0\nslow-query-log=0\nskip-log-bin\nsecure-file-priv=\""+fileIo+"\"\n"+
            (runtime.basedir()==null?"":"basedir=\""+runtime.basedir()+"\"\n")+
            (tcp?"":"skip-networking\n");
        SafeFiles.write(options,text);
        ProcessBuilder builder=new ProcessBuilder(runtime.server().toString(),"--defaults-file="+options);
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().remove("MYSQL_HOME");builder.environment().remove("MYSQL_PWD");
        process=builder.start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(config.startupTimeout());
        while (System.nanoTime()<deadline) {
            if (!process.isAlive()) throw new IOException("mariadbd terminó durante el arranque. Revise el registro privado y las bibliotecas de la imagen");
            try { if (tcp ? database.tcpReady(config.port()) : database.ready()) return; }
            catch (java.sql.SQLException | IOException ignored) { /* Reintento acotado; sin contraseñas en registro. */ }
            Thread.sleep(250);
        }
        throw new IOException("MariaDB no completó una consulta SQL autenticada dentro de startup-timeout");
    }
    private void terminate(boolean release) throws Exception {
        try {
            if (process!=null && process.isAlive()) {
                if (database!=null) { try { database.shutdown(); } catch (IOException ignored) { /* SIGTERM también solicita apagado ordenado. */ } }
                if (!process.waitFor(config.shutdownTimeout(),TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(config.shutdownTimeout(),TimeUnit.SECONDS))
                        throw new IOException("MariaDB no se detuvo. Se conserva el proceso y bloqueo; no se usa SIGKILL. Revise recuperación InnoDB");
                }
            }
            process=null;
        } finally {
            if (release && (process==null || !process.isAlive())) releaseLock();
        }
    }
    private void releaseLock() throws IOException {
        try {
            if (lock!=null) { lock.release();lock=null; }
            if (lockChannel!=null) { lockChannel.close();lockChannel=null; }
        } finally { if(localLock!=null) { LOCAL_LOCKS.remove(localLock);localLock=null; } }
    }
    public synchronized void stop() throws Exception {
        state=State.STOPPING;
        try { terminate(true);state=State.STOPPED; }
        catch (Exception e) { state=State.FAILED;throw e; }
    }
    synchronized DatabaseManager database() throws IOException { if (state()!=State.READY)throw new IOException("MariaDB no está listo");return database; }
    synchronized RuntimeManager.Runtime runtime() throws IOException { database();return runtime; }
    synchronized Path socket() throws IOException { database();return socket; }
    public String info() {
        State current=state();
        String label=switch(current) { case STOPPED -> "detenido";case STARTING -> "iniciando";case READY -> "listo";case STOPPING -> "deteniendo";case FAILED -> "fallo"; };
        return "Estado: "+label+" ("+current+"). Diagnóstico: "+diagnostic+". Reinicios automáticos: desactivados. "+(config==null?"":"Puerto SQL: "+config.port()+". MariaDB: "+RuntimeManager.VERSION+".");
    }
    @Override public void close() throws Exception { stop(); }
}
