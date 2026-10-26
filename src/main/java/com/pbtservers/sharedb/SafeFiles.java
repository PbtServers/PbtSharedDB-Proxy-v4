package com.pbtservers.sharedb;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

final class SafeFiles {
    private SafeFiles() {}
    static Path child(Path root, String name) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path result = base.resolve(name).normalize();
        if (!result.startsWith(base) || result.equals(base)) throw new IOException("Ruta fuera del directorio del plugin");
        for (Path p = result; p != null && p.startsWith(base); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IOException("No se permiten enlaces simbólicos en datos/configuración");
        }
        if (result.toString().matches(".*[\\r\\n\\\"\\\\].*") && !System.getProperty("os.name").startsWith("Windows"))
            throw new IOException("Ruta incompatible con los archivos de opciones MariaDB");
        return result;
    }
    static void directory(Path path) throws IOException {
        Files.createDirectories(path);
        restrict(path, true);
    }
    static void restrict(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }
    static void write(Path path, String content) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Archivo enlazado rechazado");
        // Crear primero con permisos privados, antes de escribir cualquier secreto.
        if (!Files.exists(path)) {
            if (Files.getFileStore(path.getParent()).supportsFileAttributeView("posix"))
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            else Files.createFile(path);
        }
        restrict(path, false);
        Files.writeString(path, content, StandardOpenOption.TRUNCATE_EXISTING);
    }
    static String secret(Path directory, String name) throws IOException {
        directory(directory);
        Path file = child(directory, name + ".key");
        if (Files.exists(file)) {
            restrict(file, false);
            String value = Files.readString(file).trim();
            if (!value.matches("[A-Za-z0-9_-]{43}")) throw new IOException("Credencial inválida; no se sobrescribe");
            return value;
        }
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        write(file, value + "\n");
        return value;
    }
}
