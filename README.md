# 🧱 Anti Handshake Flood (HFP)

## Overview

**Anti Handshake Flood (HFP)** is a high-performance Minecraft plugin developed by **sammyz (Discord: anpersonthatperson)** to mitigate handshake flood attacks.

It intelligently detects handshake flood patterns using **ProtocolLib** and **PacketEvents**, then dynamically blocks the offending IPs using `iptables` (Linux) or `netsh` (Windows).

This project is experimental but functional and built for **Minecraft 1.21.4+** servers running **Java 21** or later.

---

## ✨ Features

* 🧠 **Intelligent Detection**
  Detects handshake flood attempts in real time via ProtocolLib and PacketEvents.

* 🔒 **Automatic IP Blocking**

  * Uses `iptables` on **Linux**
  * Uses `netsh advfirewall` on **Windows**

* 🧹 **Self-Cleaning Records**
  Automatically removes inactive IP entries after the configured interval.

* ⚙️ **Configurable Thresholds**
  Customize detection and block timings through `config.yml`.

* 🪶 **Lightweight & Optimized**
  Fully asynchronous and designed for minimal performance impact.

* 💬 **In-Game Commands**

  * `/hfp reload` — reloads configuration
  * Console logging shows detailed block/unblock events

* 💻 **Cross-Platform Shell Integration**
  Automatically attempts fallback blocking with `ip route blackhole` if `iptables`/`netsh` fail.

---

## 🧩 Dependencies

* **Java 21** or higher
* **Spigot** / **Paper** / **Bukkit Based server**
* **ProtocolLib**
* **PacketEvents**
* **Minecraft 1.21.4+**

---

## ⚙️ Configuration (`config.yml`)

```yaml
#####################################################
#####################################################
#### MADE BY SAMMYZ, DISCORD: anpersonthatperson ####
#####################################################
#####################################################

# Maximum handshake packets allowed per IP within the interval
max-attempts: 5

# Time window (in milliseconds) to count handshake attempts
interval-ms: 5000

# How long to block an IP once detected (in milliseconds)
block-duration-ms: 60000

# Optional: Custom command lists for iptables/netsh integration
iptables.start:
  - "sudo iptables -N {chain}"
  - "sudo iptables -A INPUT -j {chain}"

iptables.block:
  - "sudo iptables -A {chain} -s {address} -j DROP"

iptables.stop:
  - "sudo iptables -F {chain}"
  - "sudo iptables -X {chain}"
```

---

## 🚀 Installation

1. **Download** the latest release from [Releases](link-to-releases).
2. Place `HFProtectionSAMMYZ-*.jar` in your server’s `/plugins` folder.
3. Install **ProtocolLib** and **PacketEvents** if not already present.
4. Restart your server.
5. Configure `config.yml`, since it blocks if u ping the server more than 5x, it is recommended to change it.

---

## 🧠 How It Works

1. Each incoming **handshake packet** is inspected via ProtocolLib.
2. The plugin tracks the number of handshake attempts per IP within a time window.
3. If an IP exceeds the limit:

   * The connection is cancelled immediately.
   * The IP is **blocked** using:

     * `iptables` on Linux, or
     * `netsh advfirewall` on Windows.
4. After the configured **block duration**, the IP is **automatically unblocked**.

---

## 🧰 Commands

| Command       | Description               | Permission |
| ------------- | ------------------------- | ---------- |
| `/hfp reload` | Reloads the configuration | `op` only  |

---

## 🪪 Metadata

* **Plugin Name:** Handshake Flood Protection (HFP)
* **Version:** 2.0.4
* **Author:** sammyz (Discord: `anpersonthatperson`)
* **Dependencies:** ProtocolLib, PacketEvents
* **Platform:** Spigot / Paper
* **License:** MIT or custom (if applicable)

---

## 🧩 Example Log Output

```
[HFP] Enabled — maxAttempts=5, interval=5000ms
[HFP] Blocking IP 192.168.1.15 (12 attempts)
[HFP] Unblocked 192.168.1.15
```

---

## ⚠️ Disclaimer

This is a community-driven experimental project.
Use at your own risk. Always test on a staging server before deploying in production.

---
