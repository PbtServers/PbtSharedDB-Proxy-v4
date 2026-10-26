package com.pbtservers.sharedb;

import org.apache.commons.compress.archivers.tar.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.GZIPInputStream;

public final class RuntimeManager {
    public static final String VERSION = "11.4.13";
    public static final String URL = "https://archive.mariadb.org/mariadb-11.4.13/bintar-linux-systemd-x86_64/mariadb-11.4.13-linux-systemd-x86_64.tar.gz";
    public static final String SHA256 = "3cebf63914df3154b7cb6d548337eb2be42c773aeb641110446b72e2746cfed7";
    public record Runtime(Path server, Path client, Path dump, Path installer, Path basedir, String osUser) {}
    private final Path root;
    public RuntimeManager(Path root) { this.root=root; }
    public Runtime resolve(ConfigurationManager.Config config) throws Exception {
        if (!System.getProperty("os.name").equals("Linux")) throw new IOException("MariaDB gestionado requiere Linux x86_64/aarch64. Use una imagen Linux con Java 25 y herramientas MariaDB; consulte docs/PTERODACTYL.md");
        String arch=System.getProperty("os.arch");
        if (!Set.of("amd64","x86_64","aarch64").contains(arch)) throw new IOException("Arquitectura no compatible: " + arch);
        String user=NativeCommand.capture(List.of("/usr/bin/id","-un"),root,5).trim();
        DatabaseManager.identifier(user);
        if (user.equals("root")) throw new IOException("Ejecute Velocity como usuario sin privilegios, nunca root");
        Path server=config.binary(), client=config.tools().resolve("mariadb"),dump=config.tools().resolve("mariadb-dump"),installer=config.installer(),base=null;
        if (config.runtime().equals("preinstalled")) {
            Set<Path> directories=Set.of(Path.of("/usr/bin"),Path.of("/usr/sbin"),Path.of("/usr/local/bin"),Path.of("/usr/local/sbin"),Path.of("/opt/mariadb/bin"));
            if (!directories.contains(server.getParent()) || !directories.contains(config.tools()) ||
                !(directories.contains(installer.getParent()) || Path.of("/opt/mariadb/scripts").equals(installer.getParent())))
                throw new IOException("Rutas nativas no autorizadas: use /usr/bin, /usr/sbin, /usr/local/bin, /usr/local/sbin o /opt/mariadb/bin; installer también admite /opt/mariadb/scripts");
        }
        if (config.runtime().equals("portable")) {
            Path runtime=SafeFiles.child(root,"runtime/mariadb-"+VERSION+"-linux-systemd-x86_64");
            String libc=NativeCommand.capture(List.of("/usr/bin/getconf","GNU_LIBC_VERSION"),root,5).trim();
            if (!libc.startsWith("glibc ") || arch.equals("aarch64")) throw new IOException("Portable automático solo verificado para Linux x86_64 glibc. En aarch64/musl use runtime preinstalled con herramientas nativas de la imagen");
            if (!Files.isDirectory(runtime)) {
                if (!config.automaticInstall()) throw new IOException("Distribución portable ausente; habilite automatic-install o prepare la imagen preinstalled");
                install(runtime);
            }
            base=runtime.toRealPath(); server=base.resolve("bin/mariadbd");client=base.resolve("bin/mariadb");dump=base.resolve("bin/mariadb-dump");installer=base.resolve("scripts/mariadb-install-db");
        }
        server=executable(server,"mariadbd");client=executable(client,"mariadb");dump=executable(dump,"mariadb-dump");installer=executable(installer,"mariadb-install-db");
        // El cargador comprueba arquitectura, libc y bibliotecas realmente instaladas.
        for (Path tool : List.of(server,client,dump)) {
            validateElf(tool,arch);
            String version;
            try { version=NativeCommand.capture(List.of(tool.toString(),"--no-defaults","--version"),root,10); }
            catch (IOException e) { throw new IOException("No se puede ejecutar "+tool.getFileName()+". Compruebe ldd, libc, libaio/liburing/OpenSSL, permisos y montaje noexec; consulte docs/PTERODACTYL.md",e); }
            if (!version.toLowerCase(Locale.ROOT).contains("mariadb")) throw new IOException("Herramienta no identificada como MariaDB");
            if (!version.contains(VERSION)) throw new IOException("Se exige MariaDB " + VERSION + " para servidor y clientes; no se realizan actualizaciones implícitas");
        }
        return new Runtime(server,client,dump,installer,base,user);
    }
    static void validateElf(Path path,String arch) throws IOException {
        byte[] header;
        try (InputStream in=Files.newInputStream(path)) { header=in.readNBytes(20); }
        int machine=header.length==20?(header[18]&255)|((header[19]&255)<<8):-1;
        if (header.length!=20 || header[0]!=0x7f || header[1]!='E' || header[2]!='L' || header[3]!='F' || header[4]!=2 || header[5]!=1 ||
            machine!=(arch.equals("aarch64")?183:62)) throw new IOException("Binario ELF64 incompatible con la arquitectura Linux del contenedor");
    }
    private static Path executable(Path path,String name) throws IOException {
        if (!path.getFileName().toString().equals(name) || !Files.isRegularFile(path) || !Files.isExecutable(path))
            throw new IOException("Falta herramienta ejecutable " + name + ". Inclúyala en la imagen con sus bibliotecas (libc, libaio, liburing, OpenSSL según distribución). Consulte docs/PTERODACTYL.md");
        Path resolved=path.toRealPath();
        if (!resolved.getFileName().toString().equals(name)) throw new IOException("Enlace a una herramienta distinta rechazado");
        var permissions=Files.getPosixFilePermissions(resolved);
        if (permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE) || permissions.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE))
            throw new IOException("Binario modificable por grupo/otros rechazado: "+name);
        return resolved;
    }
    private void install(Path destination) throws Exception {
        Path parent=SafeFiles.child(root,"runtime");SafeFiles.directory(parent);
        Path archive=Files.createTempFile(parent,"descarga-",".tar.gz");SafeFiles.restrict(archive,false);
        Path staging=Files.createTempDirectory(parent,"extraccion-");SafeFiles.restrict(staging,true);
        try {
            HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();
            HttpRequest request=HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofMinutes(15)).GET().build();
            HttpResponse<InputStream> response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            var watchdog=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
            try (InputStream in=response.body(); OutputStream out=Files.newOutputStream(archive)) {
                watchdog.schedule(()->{ try { response.body().close(); } catch(IOException ignored) { } },15,java.util.concurrent.TimeUnit.MINUTES);
                if (response.statusCode()!=200) throw new IOException("Descarga oficial rechazada: HTTP "+response.statusCode());
                copyLimited(in,out,1024L*1024*1024);
            } finally { watchdog.shutdownNow(); }
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try (InputStream in=new DigestInputStream(Files.newInputStream(archive),digest)) { in.transferTo(OutputStream.nullOutputStream()); }
            if (!MessageDigest.isEqual(HexFormat.of().parseHex(SHA256),digest.digest())) throw new IOException("SHA-256 oficial no coincide; no se ejecuta el artefacto");
            extract(archive,staging);
            Path extracted=staging.resolve(destination.getFileName());
            if (!Files.isDirectory(extracted)) throw new IOException("Estructura del artefacto oficial inesperada");
            Files.move(extracted,destination,StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(archive);
            // Solo temporales de instalación creados aquí; nunca datadir.
            try (var files=Files.walk(staging)) { for (Path p:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
    static void extract(Path archive,Path target) throws IOException {
        long remaining=4L*1024*1024*1024;int count=0;
        try (TarArchiveInputStream tar=new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry=tar.getNextEntry())!=null) {
                if (++count>100000 || entry.getSize()<0 || entry.getSize()>remaining) throw new IOException("Archivo portable demasiado grande");
                Path output=SafeFiles.child(target,entry.getName());
                if (entry.isSymbolicLink() || entry.isLink()) {
                    // Los enlaces de compatibilidad de la distribución no son necesarios.
                    // No se extraen: así ningún enlace puede redirigir escrituras o ejecuciones.
                    continue;
                }
                if (entry.isDirectory()) { SafeFiles.directory(output); continue; }
                if (!entry.isFile()) throw new IOException("Tipo especial de archivo rechazado");
                SafeFiles.directory(output.getParent());
                try (OutputStream out=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)) { tar.transferTo(out); }
                remaining-=entry.getSize();SafeFiles.restrict(output,false);
                if ((entry.getMode()&0111)!=0 && !output.toFile().setExecutable(true,true)) throw new IOException("No se pueden establecer permisos ejecutables");
            }
        }
    }
    private static void copyLimited(InputStream in,OutputStream out,long max) throws IOException {
        byte[] bytes=new byte[65536];int n;long total=0;
        while ((n=in.read(bytes))!=-1) { total+=n;if(total>max)throw new IOException("Descarga demasiado grande");out.write(bytes,0,n); }
    }
}
