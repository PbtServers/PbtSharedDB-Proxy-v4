package com.pbtservers.sharedb.client;

import com.pbtservers.sharedb.SafeFiles;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PbtSharedDBClient extends JavaPlugin implements CommandExecutor {
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("pbtsharedb-cliente").factory());
    private volatile boolean closing;
    private Path root;
    @Override public void onEnable() {
        root=getDataFolder().toPath().toAbsolutePath().normalize();
        try {
            SafeFiles.directory(root);Path file=SafeFiles.child(root,"config.yml");
            if(!Files.exists(file)) { try(var in=getResource("config.yml")) { SafeFiles.write(file,new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8)); } }
            Objects.requireNonNull(getCommand("pbtsharedbclient")).setExecutor(this);
            getLogger().info("Cliente cargado; use pbtsharedbclient discover tras preparar el bootstrap privado");
        } catch(Exception e) { getLogger().severe("No se pudo preparar el directorio privado del cliente");getServer().getPluginManager().disablePlugin(this); }
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof ConsoleCommandSender) && !sender.hasPermission("pbtsharedb.client.admin")) { sender.sendMessage("Sin permiso administrativo");return true; }
        if(args.length!=1 || !args[0].equalsIgnoreCase("discover")) { sender.sendMessage("Uso: /pbtsharedbclient discover");return true; }
        if(closing || !busy.compareAndSet(false,true)) { sender.sendMessage("Cliente detenido u operación en curso");return true; }
        sender.sendMessage("Comprobando proxy HTTPS y conectividad MariaDB");
        try { worker.execute(()->{
            try {
                var bootstrap=DiscoveryClient.Bootstrap.load(root);var client=new DiscoveryClient();var result=client.discover(bootstrap);
                if(closing)return;
                client.writeConnection(root,bootstrap.clientId(),result);
                getLogger().info("Conexión generada sin contraseña: "+(result.sqlAuthenticated()?"SELECT 1 autenticado":"saludo MariaDB, autenticación SQL pendiente"));
            } catch(Exception e) { getLogger().warning("Descubrimiento no completado; revise bootstrap, TLS, autenticación, estado READY y red privada. No se generan datos nuevos"); }
            finally { busy.set(false); }
        }); }catch(RejectedExecutionException e) { busy.set(false); }
        return true;
    }
    @Override public void onDisable() { closing=true;worker.shutdownNow(); }
}
