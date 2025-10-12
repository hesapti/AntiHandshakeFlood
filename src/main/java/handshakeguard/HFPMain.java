package handshakeguard;
// This is made by sammyz, aka
// discord: anpersonthatperson
// If you try to steal, or do anyhting with this without my permission will and do result and legal consenquences.

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.PacketType.Handshake.Client;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class HFPMain extends JavaPlugin {
    private int maxAttempts;
    private long intervalMs;
    private long blockDurationMs;
    private final Map<String, HFPMain.IPRecord> ipRecords = new ConcurrentHashMap();
    private final Set<String> blockedIPs = new ConcurrentSkipListSet();

    public void onEnable() {
        this.saveDefaultConfig();
        this.loadConfigValues();
        this.getLogger().info("Handshake Flood Protection enabled. Max attempts: " + this.maxAttempts);
        ProtocolManager pm = ProtocolLibrary.getProtocolManager();
        pm.addPacketListener(new PacketAdapter(this, new PacketType[]{Client.SET_PROTOCOL}) {
            public void onPacketReceiving(PacketEvent e) {
                String ip = HFPMain.this.extractIp(e);
                if (ip != null && !HFPMain.this.blockedIPs.contains(ip)) {
                    long now = System.currentTimeMillis();
                    HFPMain.IPRecord record = (HFPMain.IPRecord)HFPMain.this.ipRecords.computeIfAbsent(ip, (k) -> {
                        return new HFPMain.IPRecord();
                    });
                    record.cleanOld(now, HFPMain.this.intervalMs);
                    int count = record.addAndGet(now);
                    if (count >= HFPMain.this.maxAttempts && !HFPMain.this.blockedIPs.contains(ip)) {
                        HFPMain.this.blockedIPs.add(ip);
                        HFPMain.this.getLogger().warning("[ALERT] IP " + ip + " blocked for suspected handshake flood. Count: " + count);
                        boolean blocked = HFPMain.this.tryOSBlock(ip);
                        if (!blocked) {
                            HFPMain.this.fallbackKick(e.getPlayer(), ip);
                        }

                        Bukkit.getScheduler().runTaskLater(HFPMain.this, () -> {
                            HFPMain.this.unblockIP(ip);
                        }, Math.max(1L, HFPMain.this.blockDurationMs / 50L));
                        record.reset();
                    }

                } else {
                    e.setCancelled(true);
                }
            }
        });
    }

    private void loadConfigValues() {
        this.reloadConfig();
        this.maxAttempts = this.getConfig().getInt("max-attempts", 5);
        this.intervalMs = this.getConfig().getLong("interval-ms", 5000L);
        this.blockDurationMs = this.getConfig().getLong("block-duration-ms", 60000L);
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("hfp") && args.length > 0) {
            if (!sender.isOp()) {
                sender.sendMessage("§cYou must be an operator to use this command.");
                return true;
            }

            if (args[0].equalsIgnoreCase("reload")) {
                this.loadConfigValues();
                sender.sendMessage("§aHandshake Flood Protection config reloaded.");
                this.getLogger().info("Config reloaded via command by " + sender.getName());
                return true;
            }
        }

        return false;
    }

    private String extractIp(PacketEvent e) {
        try {
            if (e.getPlayer() != null && e.getPlayer().getAddress() != null) {
                return e.getPlayer().getAddress().getAddress().getHostAddress();
            }
        } catch (Exception var3) {
        }

        return null;
    }

    private boolean tryOSBlock(String ip) {
        String blockCmd;
        String unblockCmd;
        if (this.isLinux()) {
            blockCmd = "sudo iptables -I INPUT -s " + ip + " -j DROP";
            unblockCmd = "sudo iptables -D INPUT -s " + ip + " -j DROP";

            try {
                Process p = Runtime.getRuntime().exec(blockCmd);
                if (!p.waitFor(500L, TimeUnit.MILLISECONDS)) {
                    p.destroy();
                    throw new IOException("iptables timeout");
                }

                if (p.exitValue() == 0) {
                    this.getLogger().info("[ALERT] iptables block applied for " + ip);
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        try {
                            Runtime.getRuntime().exec(unblockCmd).waitFor(300L, TimeUnit.MILLISECONDS);
                        } catch (Exception var4) {
                            this.getLogger().warning("[ALERT] iptables unblock failed: " + var4.getMessage());
                        }

                        this.blockedIPs.remove(ip);
                    }, Math.max(1L, this.blockDurationMs / 50L));
                    return true;
                }
            } catch (Exception var7) {
                this.getLogger().info("[ALERT] iptables failed for " + ip + ": " + var7.getMessage());
            }
        } else if (this.isWindows()) {
            blockCmd = "HFPBlock_" + ip.replace(".", "_");
            unblockCmd = "netsh advfirewall firewall add rule name=\"" + blockCmd + "\" dir=in action=block remoteip=" + ip;
            String unblockCmd = "netsh advfirewall firewall delete rule name=\"" + blockCmd + "\"";

            try {
                Process p = Runtime.getRuntime().exec(unblockCmd);
                if (!p.waitFor(1L, TimeUnit.SECONDS)) {
                    p.destroy();
                    throw new IOException("netsh timeout");
                }

                if (p.exitValue() == 0) {
                    this.getLogger().info("[ALERT] netsh firewall block applied for " + ip);
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        try {
                            Runtime.getRuntime().exec(unblockCmd).waitFor(500L, TimeUnit.MILLISECONDS);
                        } catch (Exception var4) {
                            this.getLogger().warning("[ALERT] netsh unblock failed: " + var4.getMessage());
                        }

                        this.blockedIPs.remove(ip);
                    }, Math.max(1L, this.blockDurationMs / 50L));
                    return true;
                }
            } catch (Exception var6) {
                this.getLogger().info("[ALERT] netsh failed for " + ip + ": " + var6.getMessage());
            }
        }

        return false;
    }

    private void fallbackKick(Player player, String ip) {
        try {
            if (player != null && player.isOnline()) {
                player.kickPlayer("Connection blocked temporarily");
            }

            this.getLogger().info("[ALERT] Fallback block applied for " + ip);
        } catch (Exception var4) {
            this.getLogger().warning("[ALERT] fallback kick failed for " + ip + ": " + var4.getMessage());
        }

    }

    private void unblockIP(String ip) {
        this.blockedIPs.remove(ip);
        this.getLogger().info("[ALERT] IP unblocked: " + ip);
    }

    private boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("nix") || os.contains("nux") || os.contains("mac");
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    private static class IPRecord {
        private long firstTs = 0L;
        private int count = 0;

        synchronized int addAndGet(long now) {
            if (this.count == 0) {
                this.firstTs = now;
            }

            ++this.count;
            return this.count;
        }

        synchronized void cleanOld(long now, long window) {
            if (this.firstTs + window < now) {
                this.count = 0;
                this.firstTs = 0L;
            }

        }

        synchronized void reset() {
            this.count = 0;
            this.firstTs = 0L;
        }
    }
}
