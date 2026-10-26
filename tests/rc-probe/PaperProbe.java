package com.pbtservers.rcprobe;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperProbe extends JavaPlugin {
    private Snapshot snapshot;
    @Override public void onEnable() { snapshot=new Snapshot("paper"); }
    @Override public void onDisable() { if(snapshot!=null)snapshot.close(); }
}
