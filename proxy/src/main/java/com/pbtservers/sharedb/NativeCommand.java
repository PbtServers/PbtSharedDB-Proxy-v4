package com.pbtservers.sharedb;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class NativeCommand {
    private NativeCommand() {}
    static void run(List<String> args, String input, Path output, Path errors, int timeout) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(args).redirectOutput(output.toFile());
        if(output.equals(errors))builder.redirectErrorStream(true);
        else builder.redirectError(errors.toFile());
        builder.environment().remove("MYSQL_PWD"); builder.environment().remove("MYSQL_HOME");
        Process process;
        try { process=builder.start(); }
        catch (IOException e) { throw new IOException("No se pudo ejecutar la herramienta nativa; compruebe ruta, dependencias y permisos de la imagen",e); }
        try {
            // Entrada pequeña (DDL). Nunca se incluyen secretos en argumentos o logs Java.
            try (var stdin = process.getOutputStream()) { if (input != null) stdin.write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) throw new IOException("Tiempo agotado ejecutando herramienta nativa; revise el registro privado");
            if (process.exitValue() != 0) throw new IOException("La herramienta nativa terminó con código " + process.exitValue() + "; revise el registro privado");
        } finally {
            if (process.isAlive()) {
                // Un instalador puede tener un mariadbd --bootstrap propio como descendiente.
                // Conservar sus handles antes de cerrar el padre evita dejarlo huérfano.
                var children=process.descendants().toList();
                for (ProcessHandle child:children) child.destroy();
                process.destroy();
                boolean exited=process.waitFor(5,TimeUnit.SECONDS);
                for (ProcessHandle child:children) if(child.isAlive())child.destroyForcibly();
                if(!exited) { process.destroyForcibly();if(!process.waitFor(5,TimeUnit.SECONDS))throw new IOException("No se pudo cerrar la herramienta nativa tras el timeout"); }
            }
        }
    }
    static String capture(List<String> args, Path root, int timeout) throws IOException, InterruptedException {
        Path out = Files.createTempFile(root,"salida-",".tmp"), err = Files.createTempFile(root,"error-",".tmp");
        SafeFiles.restrict(out,false); SafeFiles.restrict(err,false);
        try { run(args,null,out,err,timeout); return Files.readString(out); }
        finally { Files.deleteIfExists(out); Files.deleteIfExists(err); }
    }
}
