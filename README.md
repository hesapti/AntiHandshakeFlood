# Anti Handshake Flood

## Overview

This Minecraft plugin, "Anti Handshake Flood," is designed to mitigate handshake floods by blocking Handshake Flood Protections IPs using `netsh` (on Windows) or `iptables` (on Linux) and detecting suspicious packet activity with ProtocolLib and PacketEvents. Developed by sammyz (Discord: anpersonthatperson).
Keep in mind that this is just a project. 

## Features

-   **Handshake Flood Mitigation**: Detects and blocks IPs engaging in handshake floods.
-   **Dynamic Blocking**: Uses `netsh` (Windows) or `iptables` (Linux) to block malicious IPs.
-   **Packet Analysis**: Leverages ProtocolLib and PacketEvents for real-time packet inspection.
-   **Configurable Thresholds**: Allows administrators to configure thresholds for triggering flood mitigation.
-   **Lightweight**: Optimized for minimal performance impact on the server.

## Dependencies

-   Java 21 or higher
-   Spigot/Paper server
-   ProtocolLib
-   PacketEvents
-   Minecraft Version 1.21.4

## Installation

1.  Download the latest version of the plugin from [Releases](link-to-releases).
2.  Place the `HFProtectionSAMMYZ-*.*.*.jar` file into your server's `plugins` directory.
3.  Install ProtocolLib and PacketEvents by placing their respective `.jar` files into the `plugins` directory.
4.  Restart the server.

## Configuration

The plugin's configuration file (`config.yml`) allows you to adjust various parameters:

```yaml
#####################################################
#####################################################
#### MADE BY SAMMYZ, DISCORD: anpersonthatperson ####
#####################################################
#####################################################

# Max attemps of handshake packets for each ip
max-attempts: 5

# Interval or something or idk i forgot
interval-ms: 5000

# You already read this, so u know what to put?
# uhh if u dont it means how many seconds will it block if the ip got ratelimited
block-duration-ms: 60000
```
