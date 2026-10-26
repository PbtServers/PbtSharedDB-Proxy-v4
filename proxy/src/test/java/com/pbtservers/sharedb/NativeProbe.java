package com.pbtservers.sharedb;

/** Proceso auxiliar real para comprobar el timeout sin depender de shell ni MariaDB. */
public final class NativeProbe {
    public static void main(String[] arguments) throws Exception {
        System.out.println(ProcessHandle.current().pid());System.out.flush();Thread.sleep(60000);
    }
}
