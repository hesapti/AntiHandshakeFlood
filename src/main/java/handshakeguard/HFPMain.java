package handshakeguard;
// This is made by sammyz, aka
// discord: anpersonthatperson
// If you try to steal, or do anyhting with this without my permission will and do result and legal consenquences.

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class HFPMain extends JavaPlugin {

    private static HFPMain instance;
    private int maxAttempts;
    private long intervalMs;
    private long blockDurationMs;

    private final Map<String, IPRecord> ipRecords = new ConcurrentHashMap<>();
    private final Set<String> blockedIPs = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<Channel>> ipToChannels = new ConcurrentHashMap<>();

    public static HFPMain getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadConfigValues();
        getLogger().info("Handshake Flood Protection enabled. Max Attempts: " + maxAttempts);

        ProtocolManager pm = ProtocolLibrary.getProtocolManager();
        pm.addPacketListener(new PacketAdapter(this, PacketType.Handshake.Client.SET_PROTOCOL) {
            @Override
            public void onPacketReceiving(PacketEvent e) {
                String ip = extractIp(e);
                Channel ch = tryGetChannelFromPacketEvent(e);
                if (ip == null) return;
                if (ch != null) storeChannelForIp(ip, ch);
                if (blockedIPs.contains(ip)) {
                    e.setCancelled(true);
                    if (ch != null) closeAndDropChannel(ch);
                    return;
                }

                long now = System.currentTimeMillis();
                IPRecord record = ipRecords.computeIfAbsent(ip, k -> new IPRecord());
                record.cleanOld(now, intervalMs);
                int count = record.incrementAndGet();

                if (count >= maxAttempts && blockedIPs.add(ip)) {
                    e.setCancelled(true);
                    onBlockedDetected(ip, e.getPlayer(), count);
                    injectDropHandlerToIpChannels(ip);
                    record.reset();
                }
            }
        });

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            long now = System.currentTimeMillis();
            ipRecords.entrySet().removeIf(entry -> entry.getValue().isExpired(now, intervalMs));
            ipToChannels.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isEmpty());
        }, 20L * 60, 20L * 60);
    }

    private void loadConfigValues() {
        reloadConfig();
        maxAttempts = getConfig().getInt("max-attempts", 5);
        intervalMs = getConfig().getLong("interval-ms", 5000L);
        blockDurationMs = getConfig().getLong("block-duration-ms", 60000L);
    }

    private void onBlockedDetected(String ip, Player player, int count) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            getLogger().warning("[HFP] Blocking IP " + ip + " (attempts=" + count + ")");
            Bukkit.broadcastMessage("§c[HFP] IP " + ip + " temporarily blocked! (by sammyz/ligmaligmaboy)");

            boolean didBlock = false;
            if (isWindows()) {
                didBlock = tryWindowsBlock(ip);
            } else if (isLinux()) {
                didBlock = tryLinuxBlock(ip);
            }

            if (!didBlock) fallbackKick(player, ip);

            Bukkit.getScheduler().runTaskLater(this, () -> {
                unblockIp(ip);
            }, Math.max(1L, blockDurationMs / 50L));
        });
    }

    private void unblockIp(String ip) {
        if (isWindows()) {
            try {
                String ruleName = "HFPBlock_" + ip.replace(".", "_");
                Runtime.getRuntime().exec("netsh advfirewall firewall delete rule name=\"" + ruleName + "\"");
            } catch (Exception ignored) {}
        } else if (isLinux()) {
            try {
                Runtime.getRuntime().exec("sudo iptables -D INPUT -s " + ip + " -j DROP");
            } catch (Exception ignored) {}
        }

        blockedIPs.remove(ip);
        Bukkit.broadcastMessage("§a[HFP] IP " + ip + " unblocked automatically.");
    }

    private boolean tryLinuxBlock(String ip) {
        try {
            Process p = Runtime.getRuntime().exec("sudo iptables -I INPUT -s " + ip + " -j DROP");
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                p.destroy();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception ignored) {}
        return false;
    }

    private boolean tryWindowsBlock(String ip) {
        String ruleName = "HFPBlock_" + ip.replace(".", "_");
        try {
            Process p = Runtime.getRuntime().exec(
                    "netsh advfirewall firewall add rule name=\"" + ruleName + "\" dir=in action=block remoteip=" + ip);
            if (!p.waitFor(1, TimeUnit.SECONDS)) {
                p.destroy();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception ignored) {}
        return false;
    }

    private void fallbackKick(Player player, String ip) {
        try {
            if (player != null && player.isOnline()) {
                player.kickPlayer("Blocked by Handshake Flood Protection");
            }
        } catch (Exception ignored) {}
    }

    private void storeChannelForIp(String ip, Channel ch) {
        ipToChannels.computeIfAbsent(ip, k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(ch);
    }

    private void injectDropHandlerToIpChannels(String ip) {
        Set<Channel> chans = ipToChannels.get(ip);
        if (chans == null || chans.isEmpty()) return;

        for (Channel ch : chans) {
            try {
                if (ch.pipeline().get("hfp-drop-early") == null) {
                    ch.pipeline().addFirst("hfp-drop-early", new DropAndCloseHandler(ip));
                    getLogger().info("[HFP] Injected drop handler into " + ip);
                }
                closeAndDropChannel(ch);
            } catch (Exception ex) {
                getLogger().warning("[HFP] Failed to inject for " + ip + ": " + ex.getMessage());
            }
        }

        ipToChannels.remove(ip);
    }

    private void closeAndDropChannel(Channel ch) {
        try {
            if (ch.isOpen()) ch.close();
        } catch (Exception ignored) {}
    }

    private Channel tryGetChannelFromPacketEvent(PacketEvent e) {
        try {
            Player p = e.getPlayer();
            if (p != null) {
                Object handle = p.getClass().getMethod("getHandle").invoke(p);
                for (Field f : handle.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    Object val = f.get(handle);
                    if (val != null && val.getClass().getName().toLowerCase().contains("network")) {
                        for (Field nf : val.getClass().getDeclaredFields()) {
                            nf.setAccessible(true);
                            Object possible = nf.get(val);
                            if (possible instanceof Channel) return (Channel) possible;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String extractIp(PacketEvent e) {
        try {
            if (e.getPlayer() != null && e.getPlayer().getAddress() != null) {
                InetSocketAddress addr = e.getPlayer().getAddress();
                return addr.getAddress().getHostAddress();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private boolean isLinux() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("nix") || os.contains("nux");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("hfp")) return false;
        if (!sender.isOp()) {
            sender.sendMessage("§cYou must be an operator to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§eUsage: /hfp reload");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            loadConfigValues();
            sender.sendMessage("§a[HFP] Config reloaded.");
            return true;
        }

        return false;
    }

    private static class DropAndCloseHandler extends ChannelInboundHandlerAdapter {
        private final String ip;
        DropAndCloseHandler(String ip) { this.ip = ip; }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            ctx.close();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                ReferenceCountUtil.release(msg);
            } finally {
                ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            ctx.close();
        }
    }

    private static class IPRecord {
        private volatile long firstTs = 0;
        private final AtomicInteger count = new AtomicInteger();

        int incrementAndGet() {
            if (count.get() == 0) firstTs = System.currentTimeMillis();
            return count.incrementAndGet();
        }

        void cleanOld(long now, long window) {
            if (firstTs + window < now) reset();
        }

        void reset() {
            count.set(0);
            firstTs = 0;
        }

        boolean isExpired(long now, long window) {
            return firstTs + window < now && count.get() == 0;
        }
    }
}
