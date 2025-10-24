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
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handshake Flood Protection (HFP)
 * Optimized, config-driven edition by sammyz/ligmaligmaboy
 * Version v2.0.4
 * Discord: anpersonthatperson
 */
public class HFPMain extends JavaPlugin {

    private static HFPMain instance;
    private int maxAttempts;
    private long intervalMs;
    private long blockDurationMs;
    private final Map<String, IPRecord> ipRecords = new ConcurrentHashMap<>();
    private final Set<String> blockedIPs = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<Channel>> ipToChannels = new ConcurrentHashMap<>();

    public static HFPMain getInstance() { return instance; }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadConfigValues();
        registerHandshakeListener();
        getLogger().info("§a[HFP] Enabled — maxAttempts=" + maxAttempts + ", interval=" + intervalMs + "ms");

        if (isLinux()) runCommandList("iptables.start", "hfp_chain", "0.0.0.0", 3600);
    }

    @Override
    public void onDisable() {
        if (isLinux()) runCommandList("iptables.stop", "hfp_chain", "0.0.0.0", 0);
        getLogger().info("§c[HFP] Disabled.");
    }

    private void loadConfigValues() {
        reloadConfig();
        maxAttempts = getConfig().getInt("max-attempts", 5);
        intervalMs = getConfig().getLong("interval-ms", 5000L);
        blockDurationMs = getConfig().getLong("block-duration-ms", 60000L);
    }

    private void registerHandshakeListener() {
        ProtocolManager pm = ProtocolLibrary.getProtocolManager();
        pm.addPacketListener(new PacketAdapter(this, PacketType.Handshake.Client.SET_PROTOCOL) {
            @Override
            public void onPacketReceiving(PacketEvent e) {
                String ip = getIp(e);
                if (ip == null) return;

                Channel ch = getChannel(e);
                if (ch != null) ipToChannels.computeIfAbsent(ip, k -> ConcurrentHashMap.newKeySet()).add(ch);

                if (blockedIPs.contains(ip)) {
                    e.setCancelled(true);
                    closeChannel(ch);
                    return;
                }

                long now = System.currentTimeMillis();
                IPRecord record = ipRecords.computeIfAbsent(ip, k -> new IPRecord());
                record.cleanOld(now, intervalMs);
                int count = record.incrementAndGet();

                if (count >= maxAttempts && blockedIPs.add(ip)) {
                    e.setCancelled(true);
                    asyncBlock(ip, e.getPlayer(), count);
                    record.reset();
                }
            }
        });

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            long now = System.currentTimeMillis();
            ipRecords.entrySet().removeIf(e -> e.getValue().isExpired(now, intervalMs));
        }, 20L * 30, 20L * 30);
    }

    private void asyncBlock(String ip, Player player, int count) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            getLogger().warning("[HFP] Blocking IP " + ip + " (" + count + " attempts)");
            boolean success = false;

            if (isLinux()) success = runCommandList("iptables.block", "hfp_chain", ip, 600);
            else if (isWindows()) success = runCommand("netsh advfirewall firewall add rule name=\"HFPBlock_" + ip.replace(".", "_") + "\" dir=in action=block remoteip=" + ip);

            if (!success) success = runCommand("sudo ip route add blackhole " + ip);
            if (!success) kickIfOnline(player, "§cBlocked by Handshake Flood Protection");

            Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> unblockIp(ip), blockDurationMs / 50L);
        });
    }

    private void unblockIp(String ip) {
        try {
            if (isLinux()) {
                runCommandList("iptables.stop", "hfp_chain", ip, 0);
                runCommand("sudo ip route del blackhole " + ip);
            } else if (isWindows()) {
                runCommand("netsh advfirewall firewall delete rule name=\"HFPBlock_" + ip.replace(".", "_") + "\"");
            }
        } catch (Exception ignored) {}

        blockedIPs.remove(ip);
        Bukkit.broadcastMessage("§a[HFP] Unblocked " + ip);
    }

    private boolean runCommand(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            while (reader.ready()) getLogger().info("[HFP-SHELL] " + reader.readLine());

            boolean done = p.waitFor(2, TimeUnit.SECONDS);
            if (!done) p.destroy();
            return p.exitValue() == 0;
        } catch (Exception ex) {
            handleMissingSudo(ex);
            return false;
        }
    }

    private boolean runCommandList(String path, String chain, String ip, int time) {
        List<String> cmds = getConfig().getStringList(path);
        if (cmds == null || cmds.isEmpty()) return false;

        boolean allOk = true;
        for (String raw : cmds) {
            String cmd = raw.replace("{chain}", chain)
                    .replace("{address}", ip)
                    .replace("{time}", String.valueOf(time));
            if (!runCommand(cmd)) allOk = false;
        }
        return allOk;
    }

    private void handleMissingSudo(Exception ex) {
        if (ex.getMessage() != null && ex.getMessage().contains("No such file or directory")) {
            getLogger().warning("[HFP] Missing sudo or shell, attempting fix...");
            runCommand("echo $PATH");
            runCommand("which sudo");
            runCommand("apt-get update && apt-get install -y sudo");
        }
    }

    private String getIp(PacketEvent e) {
        try {
            InetSocketAddress addr = e.getPlayer().getAddress();
            return addr != null ? addr.getAddress().getHostAddress() : null;
        } catch (Exception ignored) {}
        return null;
    }

    private Channel getChannel(PacketEvent e) {
        try {
            Object handle = e.getPlayer().getClass().getMethod("getHandle").invoke(e.getPlayer());
            for (var f : handle.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(handle);
                if (val != null && val.getClass().getName().toLowerCase().contains("network")) {
                    for (var nf : val.getClass().getDeclaredFields()) {
                        nf.setAccessible(true);
                        Object possible = nf.get(val);
                        if (possible instanceof Channel ch) return ch;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void closeChannel(Channel ch) {
        if (ch != null && ch.isOpen()) ch.close();
    }

    private void kickIfOnline(Player p, String msg) {
        if (p != null && p.isOnline()) p.kickPlayer(msg);
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private boolean isLinux() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("nux") || os.contains("nix");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("hfp")) return false;
        if (!sender.isOp()) return true;

        if (args.length == 0 || args[0].equalsIgnoreCase("reload")) {
            loadConfigValues();
            sender.sendMessage("§a[HFP] Reloaded configuration.");
            return true;
        }
        return false;
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

    private static class DropHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ReferenceCountUtil.release(msg);
            ctx.close();
        }
    }
}

