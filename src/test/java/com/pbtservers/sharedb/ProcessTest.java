package com.pbtservers.sharedb;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcessTest {
    @TempDir Path root;
    @Test void disabledStartDoesNotModifyData() throws Exception {
        var c=new ConfigurationManager(root).load();var p=new ProcessManager(root);
        assertThrows(IOException.class,()->p.start(c));assertEquals(ProcessManager.State.STOPPED,p.state());assertFalse(Files.exists(c.data()));p.stop();assertEquals(ProcessManager.State.STOPPED,p.state());
    }
    @Test void occupiedPortIsRejected() throws Exception {
        try (ServerSocket socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            assertThrows(IOException.class,()->ProcessManager.checkPort("127.0.0.1",socket.getLocalPort()));
        }
    }
    @Test void validatesNativeExitCode() throws Exception {
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        assertThrows(IOException.class,()->NativeCommand.run(List.of(java.toString(),"--opcion-inexistente"),null,root.resolve("out"),root.resolve("err"),10));
        assertDoesNotThrow(()->NativeCommand.run(List.of(java.toString(),"-version"),null,root.resolve("out"),root.resolve("err"),10));
    }
    @Test void pathsCannotEscapeRoot() { assertThrows(IOException.class,()->SafeFiles.child(root,"../otro"));assertThrows(IOException.class,()->SafeFiles.child(root,".")); }
    @Test void nativeTimeoutTerminatesOnlyTheOwnedProcess() throws Exception {
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        String classes=Path.of(NativeProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path out=root.resolve("probe-out"),err=root.resolve("probe-err");
        assertThrows(IOException.class,()->NativeCommand.run(List.of(java.toString(),"-cp",classes,NativeProbe.class.getName()),null,out,err,2));
        long pid=Long.parseLong(Files.readString(out).trim());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));assertTrue(ProcessHandle.current().isAlive());
    }
}
