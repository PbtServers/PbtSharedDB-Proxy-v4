package com.pbtservers.rcprobe;

import java.util.Timer;
import java.util.TimerTask;
import net.luckperms.api.LuckPermsProvider;

// Solo observa la caché real de LuckPerms: no escribe SQL ni carga datos del storage.
final class Snapshot {
    private final Timer timer=new Timer("rc-observador-luckperms",true);
    private String previous="";
    Snapshot(String platform) {
        timer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                try {
                    var group=LuckPermsProvider.get().getGroupManager().getGroup("default");
                    String value=group==null?"NO_GROUP":group.getCachedData().getPermissionData().checkPermission("pbt.rc1.propagacion").name();
                    if(!value.equals(previous)) { System.out.println("RC_CACHE "+platform+" "+value);previous=value; }
                } catch (IllegalStateException e) { /* Esperar a la carga real de LP, sin simular resultados. */ }
            }
        },0,250);
    }
    void close() { timer.cancel(); }
}
