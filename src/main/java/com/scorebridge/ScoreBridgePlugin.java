package com.scorebridge;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;

@Plugin(id="scorebridge", name="ScoreBridge", version="1.0.1")
public final class ScoreBridgePlugin {
 private static final ChannelIdentifier CHANNEL=MinecraftChannelIdentifier.create("scorebridge","control");
 private final ProxyServer proxy; private final Logger logger;
 private final Map<UUID,Set<String>> owners=new HashMap<>(); private final Map<UUID,Boolean> previous=new HashMap<>();
 @Inject public ScoreBridgePlugin(ProxyServer proxy,Logger logger){this.proxy=proxy;this.logger=logger;}
 @Subscribe public void onProxyInitialize(ProxyInitializeEvent event){proxy.getChannelRegistrar().register(CHANNEL);logger.info("ScoreBridge initialized; plugin messaging channel registered.");}
 @Subscribe public void disconnect(DisconnectEvent e){UUID id=e.getPlayer().getUniqueId();owners.remove(id);previous.remove(id);}
 @Subscribe public void message(PluginMessageEvent e){if(!CHANNEL.equals(e.getIdentifier()))return;e.setResult(PluginMessageEvent.ForwardResult.handled());try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(e.getData()))){String op=in.readUTF(),owner=in.readUTF(),rawUuid=in.readUTF();UUID id=UUID.fromString(rawUuid);Player player=proxy.getPlayer(id).orElse(null);if(player==null||owner.isBlank())return;Set<String> set=owners.computeIfAbsent(id,k->new HashSet<>());if("ACQUIRE".equalsIgnoreCase(op)){if(set.add(owner)&&set.size()==1){previous.put(id,visible(player));setVisible(player,false);}}else if("RELEASE".equalsIgnoreCase(op)){set.remove(owner);if(set.isEmpty()){owners.remove(id);setVisible(player,previous.getOrDefault(id,true));previous.remove(id);}}}catch(Exception ex){logger.warn("Invalid ScoreBridge message",ex);}}
 private Object[] tab(Player p)throws Exception{Class<?> api=Class.forName("me.neznamy.tab.api.TabAPI");Object instance=api.getMethod("getInstance").invoke(null);Object tabPlayer=api.getMethod("getPlayer",UUID.class).invoke(instance,p.getUniqueId());Object manager=api.getMethod("getScoreboardManager").invoke(instance);return new Object[]{manager,tabPlayer};}
 private boolean visible(Player p){try{Object[] x=tab(p);Class<?> type=Class.forName("me.neznamy.tab.api.TabPlayer");return(Boolean)x[0].getClass().getMethod("hasScoreboardVisible",type).invoke(x[0],x[1]);}catch(Exception ex){return true;}}
 private void setVisible(Player p,boolean visible){String action=visible?"on":"off";proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(),"btab scoreboard "+action+" "+p.getUsername()+" -s").whenComplete((result,error)->{if(error!=null)logger.warn("Unable to toggle TAB scoreboard for "+p.getUsername(),error);else logger.info("ScoreBridge {} TAB scoreboard for {} via btab",action,p.getUsername());});}
}
