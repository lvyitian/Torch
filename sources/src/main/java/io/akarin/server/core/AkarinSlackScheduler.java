package io.akarin.server.core;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

import io.akarin.api.internal.Akari;
import net.minecraft.server.EntityPlayer;
import net.minecraft.server.EnumDifficulty;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PacketPlayOutKeepAlive;
import net.minecraft.server.PacketPlayOutPlayerInfo;
import net.minecraft.server.PacketPlayOutUpdateTime;
import net.minecraft.server.PlayerConnection;
import net.minecraft.server.WorldServer;

public class AkarinSlackScheduler extends Thread {
    public static AkarinSlackScheduler get() {
        return Singleton.instance;
    }
    
    public static void boot() {
        Singleton.instance.setName("Akarin Slack Scheduler Thread");
        Singleton.instance.setPriority(MIN_PRIORITY);
        Singleton.instance.setDaemon(true);
        Singleton.instance.start();
        Akari.logger.info("Slack scheduler service started");
    }
    
    private static class Singleton {
        private static final AkarinSlackScheduler instance = new AkarinSlackScheduler();
    }
    
    /*
     * Timers
     */
    private long updateTime;
    private long resendPlayersInfo;

    @Override
    public void run() {
        MinecraftServer server = MinecraftServer.getServer();
        
        while (server.isRunning()) {
            // Send time updates to everyone, it will get the right time from the world the player is in.
            // Time update, from MinecraftServer#D
            if (++updateTime >= AkarinGlobalConfig.timeUpdateInterval) {
                for (EntityPlayer player : server.getPlayerList().players) {
                    player.playerConnection.sendPacket(new PacketPlayOutUpdateTime(player.world.getTime(), player.getPlayerTime(), player.world.getGameRules().getBoolean("doDaylightCycle"))); // Add support for per player time
                }
                updateTime = 0;
            }
            
            // Keep alive, from PlayerConnection#e
            for (EntityPlayer player : server.getPlayerList().players) {
                PlayerConnection conn = player.playerConnection;
                // Paper - give clients a longer time to respond to pings as per pre 1.12.2 timings
                // This should effectively place the keepalive handling back to "as it was" before 1.12.2
                long currentTime = System.nanoTime() / 1000000L;
                long elapsedTime = currentTime - conn.getLastPing();
                if (conn.isPendingPing()) {
                    // We're pending a ping from the client
                    if (!conn.processedDisconnect && elapsedTime >= AkarinGlobalConfig.keepAliveTimeout) { // check keepalive limit, don't fire if already disconnected
                        Akari.callbackQueue.add(() -> {
                            Akari.logger.warn("{} was kicked due to keepalive timeout!", conn.player.getName()); // more info
                            conn.disconnect("disconnect.timeout");
                        });
                    }
                } else {
                    if (elapsedTime >= AkarinGlobalConfig.keepAliveSendInterval) { // 15 seconds default
                        conn.setPendingPing(true);
                        conn.setLastPing(currentTime);
                        conn.setKeepAliveID(currentTime);
                        conn.sendPacket(new PacketPlayOutKeepAlive(conn.getKeepAliveID()));
                    }
                }
            }
            
            // Force hardcore difficulty, from WorldServer#doTick
            for (WorldServer world : server.worlds) {
                if (world.getWorldData().isHardcore() && world.getDifficulty() != EnumDifficulty.HARD) {
                    world.getWorldData().setDifficulty(EnumDifficulty.HARD);
                }
            }
            
            // Update player info, from PlayerList#tick
            if (++resendPlayersInfo > AkarinGlobalConfig.playersInfoUpdateInterval) {
                for (EntityPlayer target : server.getPlayerList().players) {
                    target.playerConnection.sendPacket(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.UPDATE_LATENCY, Iterables.filter(server.getPlayerList().players, new Predicate<EntityPlayer>() {
                        @Override
                        public boolean apply(EntityPlayer input) {
                            return target.getBukkitEntity().canSee(input.getBukkitEntity());
                        }
                    })));
                }
                resendPlayersInfo = 0;
            }
            
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Akari.logger.warn("Slack scheduler thread was interrupted unexpectly!");
                ex.printStackTrace();
            }
        }
    }
    
}
