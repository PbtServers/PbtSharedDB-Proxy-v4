package com.pbtservers.sharedb;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class YamlFiles {
    private YamlFiles() {}
    public static Map<?,?> read(Path file) throws IOException {
        LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);options.setCodePointLimit(131072);
        try(var reader=Files.newBufferedReader(file)) {
            Object result=new Yaml(new SafeConstructor(options)).load(reader);
            if(!(result instanceof Map<?,?> map))throw new IOException("Se esperaba un mapa YAML");
            return map;
        } catch(RuntimeException e) { throw new IOException("YAML inválido; revise claves, tipos y duplicados",e); }
    }
    public static String text(Map<?,?> m,String key) { if(m.get(key) instanceof String s && !s.isBlank())return s;throw new IllegalArgumentException("Falta texto: "+key); }
    public static boolean flag(Map<?,?> m,String key) { if(m.get(key) instanceof Boolean b)return b;throw new IllegalArgumentException("Falta booleano: "+key); }
    public static int port(Map<?,?> m,String key) { Object value=m.get(key);if(value instanceof Number n && n.doubleValue()==n.intValue() && n.intValue()>=1024 && n.intValue()<=65535)return n.intValue();throw new IllegalArgumentException("Puerto inválido"); }
}
