package com.pbtservers.sharedb;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class BackupManager {
    private final Path root;
    public BackupManager(Path root) { this.root=root; }
    public Path backup(ProcessManager manager, ConfigurationManager.Config config) throws Exception {
        if (config.databases().isEmpty()) throw new IOException("No hay bases declaradas para copiar");
        DatabaseManager database=manager.database();
        // FTWRL durante TODO el dump impide DDL y escrituras concurrentes, también tablas no InnoDB.
        // No se usa una copia de archivos activos ni se anuncia consistencia sin bloquear DDL.
        Path directory=SafeFiles.child(root,"backups");SafeFiles.directory(directory);
        long estimate=Long.parseLong(database.sql("SELECT COALESCE(SUM(data_length+index_length),0) FROM information_schema.tables WHERE table_schema NOT IN ('mysql','sys','information_schema','performance_schema');\n",10));
        long free=Files.getFileStore(directory).getUsableSpace();
        if (free<config.minimumFreeBytes()+Math.max(estimate*2,64L*1024*1024)) throw new IOException("Espacio insuficiente para la copia lógica y margen de seguridad");
        String name="copia-"+DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now())+"-"+UUID.randomUUID()+".sql";
        Path partial=SafeFiles.child(directory,name+".partial"),complete=SafeFiles.child(directory,name),errors=SafeFiles.child(directory,name+".stderr");
        SafeFiles.write(partial,"");SafeFiles.write(errors,"");
        // --lock-all-tables obtiene el bloqueo global en la propia sesión del dump.
        List<String> args=new ArrayList<>(List.of(manager.runtime().dump().toString(),"--no-defaults","--protocol=socket","--socket="+manager.socket(),"--user="+manager.runtime().osUser(),"--lock-all-tables","--quick","--hex-blob","--default-character-set=utf8mb4","--databases"));
        args.addAll(config.databases());
        try {
            NativeCommand.run(args,null,partial,errors,config.backupTimeout());
            if (Files.size(partial)==0 || Files.size(errors)>0) throw new IOException("Copia vacía o herramienta con diagnósticos; revise el archivo stderr privado");
            Files.move(partial,complete,StandardCopyOption.ATOMIC_MOVE);
            Files.delete(errors);
            try (var entries=Files.list(directory)) {
                var copies=entries.filter(p->p.getFileName().toString().matches("copia-[0-9]{8}-[0-9]{6}-[0-9]{3}-[a-f0-9-]{36}\\.sql")).sorted(Comparator.comparing(Path::toString).reversed()).toList();
                for (int i=config.retention();i<copies.size();i++) Files.delete(copies.get(i));
            }
            return complete;
        } catch (Exception e) { Files.deleteIfExists(partial);throw e; }
    }
}
