package com.pbtservers.sharedb;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import net.kyori.adventure.text.Component;
import java.util.*;

public final class CommandManager implements SimpleCommand {
    private static final List<String> ACTIONS=List.of("help","status","info","start","stop","restart","backup","reload");
    private final PbtSharedDB plugin;
    public CommandManager(PbtSharedDB plugin) { this.plugin=plugin; }
    @Override public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof ConsoleCommandSource || invocation.source().hasPermission("pbtsharedb.admin");
    }
    @Override public void execute(Invocation invocation) {
        if (!hasPermission(invocation)) { invocation.source().sendMessage(Component.text("No tiene permiso para administrar PbtSharedDB"));return; }
        String action=invocation.arguments().length==0?"help":invocation.arguments()[0].toLowerCase(Locale.ROOT);
        if (invocation.arguments().length>1 || !ACTIONS.contains(action)) { invocation.source().sendMessage(Component.text("Uso: /pbtsharedb "+String.join(" | ",ACTIONS)));return; }
        if (action.equals("help")) invocation.source().sendMessage(Component.text("PbtSharedDB: "+String.join(", ",ACTIONS)+". Administración: consola o permiso pbtsharedb.admin. El backup bloquea escrituras hasta terminar."));
        else if (action.equals("status")||action.equals("info")) invocation.source().sendMessage(Component.text(plugin.status()));
        else plugin.execute(action,message->invocation.source().sendMessage(Component.text(message)));
    }
    @Override public List<String> suggest(Invocation invocation) {
        if (!hasPermission(invocation)||invocation.arguments().length>1)return List.of();
        String prefix=invocation.arguments().length==0?"":invocation.arguments()[0].toLowerCase(Locale.ROOT);
        return ACTIONS.stream().filter(s->s.startsWith(prefix)).toList();
    }
}
