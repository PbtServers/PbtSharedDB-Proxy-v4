package com.pbtservers.rcprobe;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;

@Plugin(id="rcprobe",name="Observador RC",version="1",dependencies={@Dependency(id="luckperms")})
public final class VelocityProbe {
    private Snapshot snapshot;
    @Subscribe public void start(ProxyInitializeEvent event) { snapshot=new Snapshot("velocity"); }
    @Subscribe public void stop(ProxyShutdownEvent event) { if(snapshot!=null)snapshot.close(); }
}
