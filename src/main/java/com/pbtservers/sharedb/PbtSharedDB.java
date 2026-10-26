package com.pbtservers.sharedb;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import java.nio.file.Path;
import java.util.concurrent.*;

@Plugin(id="pbtsharedb",name="PbtSharedDB",version="1.0.0-SNAPSHOT",authors={"PbtServers"},description="Servidor MariaDB nativo administrado por Velocity")
public final class PbtSharedDB {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path root;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("pbtsharedb-administracion").factory());
    private final ProcessManager processes;
    private ConfigurationManager configurations;
    private volatile ConfigurationManager.Config config;
    private volatile boolean closing;
    @Inject public PbtSharedDB(ProxyServer proxy,Logger logger,@DataDirectory Path directory) {
        this.proxy=proxy;this.logger=logger;this.root=directory.toAbsolutePath().normalize();this.processes=new ProcessManager(root);
    }
    // FIRST equivale a MAX_VALUE-1 en Velocity: LuckPerms debe encontrar SQL listo.
    @Subscribe(priority=Short.MAX_VALUE) public EventTask initialize(ProxyInitializeEvent event) {
        return EventTask.withContinuation(continuation -> executor.execute(() -> {
            try {
                configurations=new ConfigurationManager(root);
                var metadata=proxy.getCommandManager().metaBuilder("pbtsharedb").aliases("sharedb").plugin(this).build();
                proxy.getCommandManager().register(metadata,new CommandManager(this));
                config=configurations.load();
                if (config.enabled()&&config.autoStart()) processes.start(config);
                logger.info("PbtSharedDB: {}",processes.info());
            } catch (Exception e) { logger.error("PbtSharedDB no está operativo: {}",safeError(e)); }
            finally { continuation.resume(); }
        }));
    }
    // Cerrar SQL después de los consumidores que escuchan en LAST (p. ej. LuckPerms).
    @Subscribe(priority=Short.MIN_VALUE) public EventTask shutdown(ProxyShutdownEvent event) {
        closing=true;
        return EventTask.withContinuation(continuation -> executor.execute(() -> {
            try { processes.stop();logger.info("Gestor MariaDB cerrado; no quedan procesos propios activos"); }
            catch (Exception e) { logger.error("Apagado MariaDB incompleto: {}",safeError(e)); }
            finally { executor.shutdown();continuation.resume(); }
        }));
    }
    void execute(String action,java.util.function.Consumer<String> reply) {
        if (closing) { reply.accept("El proxy se está apagando");return; }
        // La cola acotada evita acumular operaciones sensibles. El comando no bloquea Velocity.
        if (!pending.compareAndSet(false,true)) { reply.accept("Ya hay una operación administrativa en curso");return; }
        try { executor.execute(() -> {
            try {
                switch(action) {
                    case "start" -> { requireConfig();processes.start(config);reply.accept("MariaDB listo para consultas autenticadas"); }
                    case "stop" -> { processes.stop();reply.accept("MariaDB detenido"); }
                    case "restart" -> { requireConfig();processes.stop();processes.start(config);reply.accept("MariaDB reiniciado y disponible"); }
                    case "backup" -> { requireConfig();Path file=new BackupManager(root).backup(processes,config);reply.accept("Copia consistente creada: "+file.getFileName()); }
                    case "reload" -> {
                        if (processes.state()!=ProcessManager.State.STOPPED && processes.state()!=ProcessManager.State.FAILED) throw new IllegalStateException("Detenga MariaDB antes de recargar la configuración");
                        var loaded=configurations.load();config=loaded;reply.accept("Configuración validada y recargada; use start para iniciar");
                    }
                    default -> reply.accept("Acción desconocida; use /pbtsharedb help");
                }
            } catch (Exception e) { String message=safeError(e);logger.error("Operación {} fallida: {}",action,message);reply.accept("Operación fallida: "+message); }
            finally { pending.set(false); }
        }); } catch (RejectedExecutionException e) { pending.set(false);reply.accept("El administrador está detenido"); }
    }
    private final java.util.concurrent.atomic.AtomicBoolean pending=new java.util.concurrent.atomic.AtomicBoolean();
    private void requireConfig() { if (config==null)throw new IllegalStateException("Configuración no disponible; revise config.yml y recargue"); }
    String status() { return processes.info(); }
    static String safeError(Exception e) {
        // Nunca imprimir SQLException/causas: pueden incluir SQL y credenciales del proveedor.
        if (e instanceof java.sql.SQLException) return "Error SQL; compruebe autenticación y configuración";
        if (e instanceof java.io.IOException || e instanceof IllegalStateException) return e.getMessage()==null?"Error de administración":e.getMessage();
        if (e instanceof InterruptedException) { Thread.currentThread().interrupt();return "Operación interrumpida"; }
        return "Error de administración ("+e.getClass().getSimpleName()+"); revise configuración y registros privados";
    }
}
