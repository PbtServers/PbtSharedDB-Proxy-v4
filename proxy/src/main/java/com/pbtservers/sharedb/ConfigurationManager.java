package com.pbtservers.sharedb;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class ConfigurationManager {
    public record User(String name, String host, String database) {}
    public record Config(boolean enabled, boolean autoStart, String runtime, Path binary, Path tools,
                         Path installer, boolean automaticInstall, Path data, String host, int port,
                         int startupTimeout, int shutdownTimeout, boolean initialize, List<String> databases,
                         List<User> users, int retention, int backupTimeout, long minimumFreeBytes, String log) {}
    private final Path root;
    public ConfigurationManager(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public Config load() throws IOException {
        SafeFiles.directory(root);
        Path file = SafeFiles.child(root, "config.yml");
        if (!Files.exists(file)) {
            try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                if (in == null) throw new IOException("Falta config.yml en el JAR");
                SafeFiles.write(file, new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        try {
            Map<?, ?> m = YamlFiles.read(file);
            if (number(m, "config-version", 1, 1) != 1) throw new IllegalArgumentException("Versión de configuración no compatible");
            String host = string(m, "host");
            if (!host.equals("127.0.0.1") && !host.equals("0.0.0.0")) throw new IllegalArgumentException("host debe ser 127.0.0.1 o 0.0.0.0");
            int port = number(m, "port", 1024, 65535);
            if (host.equals("0.0.0.0") && (!bool(m,"remote-access") || !bool(m,"network-isolation-confirmed") || number(m,"allocated-port",1024,65535) != port))
                throw new IllegalArgumentException("Acceso remoto: confirme aislamiento de red y asignación adicional con el mismo puerto");
            String runtime = string(m,"runtime");
            if (!Set.of("preinstalled","portable").contains(runtime)) throw new IllegalArgumentException("runtime inválido");
            List<String> databases = new ArrayList<>();
            for (Object value : list(m,"databases")) { String db = Objects.toString(value); DatabaseManager.identifier(db); if (Set.of("mysql","sys","information_schema","performance_schema","test").contains(db.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Base reservada"); databases.add(db); }
            if (new HashSet<>(databases).size() != databases.size()) throw new IllegalArgumentException("Bases duplicadas");
            List<User> users = new ArrayList<>(); Set<String> identities = new HashSet<>();
            for (Object value : list(m,"users")) {
                Map<?,?> u = map(value); String name = string(u,"name"), address = string(u,"host"), db = string(u,"database");
                DatabaseManager.identifier(name); DatabaseManager.authorizedHost(address);
                if (Set.of("root","mysql","mariadb.sys","pbt_health").contains(name) || name.length() > 32 || !databases.contains(db) || !identities.add(name+"@"+address)) throw new IllegalArgumentException("Usuario reservado, duplicado o base no declarada");
                users.add(new User(name,address,db));
            }
            Map<?,?> backup = map(m.get("backups")); String log = string(map(m.get("logs")),"error-file");
            if (!log.matches("[A-Za-z0-9_-]+\\.log")) throw new IllegalArgumentException("Nombre de log inválido");
            String dataName=string(m,"data-directory");
            if (!dataName.matches("data(?:/[A-Za-z0-9_-]+)*")) throw new IllegalArgumentException("data-directory debe ser data o un subdirectorio suyo");
            return new Config(bool(m,"enabled"), bool(m,"auto-start"), runtime,
                absolute(string(m,"binary-path")), absolute(string(m,"tools-directory")), absolute(string(m,"install-db-path")),
                bool(m,"automatic-install"), SafeFiles.child(root,string(m,"data-directory")),host,port,
                number(m,"startup-timeout",5,600),number(m,"shutdown-timeout",5,600),bool(map(m.get("initialization")),"enabled"),
                List.copyOf(databases),List.copyOf(users),number(backup,"retention",1,365),number(backup,"timeout",5,86400),
                (long)number(backup,"minimum-free-mib",64,1048576)*1024*1024,log);
        } catch (RuntimeException e) { throw new IOException("Configuración inválida: revise tipos, claves y restricciones en docs/CONFIGURACION.md", e); }
    }
    private static Path absolute(String s) { Path p=Path.of(s); if ((!p.isAbsolute() && !s.startsWith("/")) || s.contains("\n") || s.contains("\r")) throw new IllegalArgumentException("Se requiere ruta absoluta"); return p.normalize(); }
    private static Map<?,?> map(Object o) { if (!(o instanceof Map<?,?> m)) throw new IllegalArgumentException("Se esperaba mapa"); return m; }
    private static String string(Map<?,?> m,String key) { if (!(m.get(key) instanceof String s) || s.isBlank()) throw new IllegalArgumentException("Falta texto: "+key); return s; }
    private static boolean bool(Map<?,?> m,String key) { if (!(m.get(key) instanceof Boolean b)) throw new IllegalArgumentException("Falta booleano: "+key); return b; }
    private static List<?> list(Map<?,?> m,String key) { if (!(m.get(key) instanceof List<?> l)) throw new IllegalArgumentException("Falta lista: "+key); return l; }
    private static int number(Map<?,?> m,String key,int min,int max) { if (!(m.get(key) instanceof Number n) || n.doubleValue()!=n.intValue() || n.intValue()<min || n.intValue()>max) throw new IllegalArgumentException("Entero fuera de rango: "+key); return n.intValue(); }
}
