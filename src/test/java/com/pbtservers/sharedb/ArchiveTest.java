package com.pbtservers.sharedb;

import org.apache.commons.compress.archivers.tar.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveTest {
    @TempDir Path root;
    Path archive(String name,byte type,String link) throws Exception {
        Path path=root.resolve("test.tar.gz");
        try (var tar=new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
            var e=new TarArchiveEntry(name,type);if(link!=null)e.setLinkName(link);e.setSize(0);tar.putArchiveEntry(e);tar.closeArchiveEntry();
        }
        return path;
    }
    @Test void rejectsTraversal() throws Exception {
        Path target=root.resolve("target");Files.createDirectory(target);
        Path archive=archive("../escape",TarConstants.LF_NORMAL,null);assertThrows(IOException.class,()->RuntimeManager.extract(archive,target));assertFalse(Files.exists(root.resolve("escape")));
    }
    @Test void rejectsAbsoluteEntry() throws Exception {
        Path target=root.resolve("target");Files.createDirectory(target);
        // TarArchiveEntry normaliza rutas absolutas salvo preserveAbsolutePath.
        try (var tar=new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(root.resolve("absolute.tar.gz"))))) {
            var entry=new TarArchiveEntry("/etc/escape",true);entry.setSize(0);tar.putArchiveEntry(entry);tar.closeArchiveEntry();
        }
        assertThrows(IOException.class,()->RuntimeManager.extract(root.resolve("absolute.tar.gz"),target));
    }
    @Test void neverExtractsLinks() throws Exception {
        Path target=root.resolve("target");Files.createDirectory(target);
        RuntimeManager.extract(archive("link",TarConstants.LF_SYMLINK,"../../fuera"),target);assertFalse(Files.exists(target.resolve("link"),LinkOption.NOFOLLOW_LINKS));
    }
    @Test void rejectsNonElfAndWrongArchitecture() throws Exception {
        Path binary=root.resolve("mariadbd");Files.writeString(binary,"#!/bin/sh\necho MariaDB\n");
        assertThrows(IOException.class,()->RuntimeManager.validateElf(binary,"amd64"));
        byte[] elf=new byte[20];elf[0]=0x7f;elf[1]='E';elf[2]='L';elf[3]='F';elf[4]=2;elf[5]=1;elf[18]=62;
        Files.write(binary,elf);assertDoesNotThrow(()->RuntimeManager.validateElf(binary,"amd64"));
        assertThrows(IOException.class,()->RuntimeManager.validateElf(binary,"aarch64"));
    }
}
