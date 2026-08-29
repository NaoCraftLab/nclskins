# 🌐 NCL Skins Plugin

<a href="https://www.curseforge.com/minecraft/mc-mods/nclskins">
  <img src="https://img.shields.io/static/v1?label=CurseForge&amp;message=NCL%20Skins&amp;color=fb4e44&amp;logo=curseforge&amp;logoColor=white&amp;style=for-the-badge" alt="NCL Skins on CurseForge" />
</a>

NCL Skins Plugin is the optional server companion for NCL Skins. After Minecraft confirms an official skin or cape change, the plugin updates that player's appearance for everyone else on the server without requiring the player to disconnect and rejoin.

NCL Skins must be installed on the client of the player changing their appearance. Other players will see the update in the world and player list even if they do not have the mod installed.

## 🛡️ Security and stability at high player counts

The plugin only accepts a request from the currently connected player with a verified identity, rechecks that player's official Minecraft profile, and publishes only the verified skin or cape from that profile. Concurrent requests, their average rate, and short bursts are limited. Repeated requests from one player are combined so that only the latest relevant change is applied.

When many players need to receive an appearance update, the work is spread across server ticks instead of running all at once. A failed refresh for one viewer does not block the others, while stale requests and requests from disconnected players are safely removed from the queue.

## 🧩 One plugin for servers and proxies

One universal JAR contains separate entrypoints for Bukkit-family servers, Velocity, and BungeeCord.

- On a standalone server, install the JAR in the `plugins` folder
- On a proxy network, install the same JAR on the proxy and every backend server
- The plugin does not change skins by itself or poll profiles continuously. A refresh starts only after NCL Skins confirms a change
- Without the plugin, the official appearance change will still succeed, but other players may need to reconnect, or the changed player may need to be reloaded by the server

## 🛠️ Compatibility

| Minecraft version | Standalone server | Velocity network | BungeeCord network |
|---|---|---|---|
| 1.20.1 | CraftBukkit, Spigot, Paper, Purpur, Folia | Paper, Purpur, Folia | Spigot, Paper, Purpur, Folia |
| 1.21.1 | Paper, Purpur | Paper, Purpur | Paper, Purpur |
| 1.21.11 | Paper, Purpur, Folia | Paper, Purpur, Folia | Paper, Purpur, Folia |
| 26.1.1 | Paper | Paper | Paper |
| 26.1.2 | Paper, Purpur, Folia | Paper, Purpur, Folia | Paper, Purpur, Folia |
| 26.2 | Paper, Purpur, Folia | Paper, Purpur, Folia | Paper, Purpur, Folia |

When client-server interaction changes, an updated plugin is released with the same version as the NCL Skins mod that introduces the change. Compatibility with older mod versions is not guaranteed. That plugin release becomes the new compatibility baseline: later mod versions remain compatible with it until another client-server change requires a new same-version plugin release.

## ⚙️ Configuration

| Platform | Location | Available settings |
|---|---|---|
| CraftBukkit, Spigot, Paper, Purpur, Folia | `plugins/NCLSkinsPlugin/nclskins-server.json5` | Appearance refresh without reconnecting, trust for verified proxy forwarding, concurrent profile lookups, average request rate, and maximum short-term burst |
| Velocity, BungeeCord | NCL Skins Plugin has no separate configuration file on the proxy | The plugin works without configuration. Configure the applicable verified forwarding mechanism described below on the proxy and backend servers |

The standalone or backend server configuration is created automatically after the first startup. For a proxy network, set `realtimeRefresh.trustedProxyForwarding: true` on every backend server. Changes take effect after a server restart.

## 🔐 Proxy network requirements

- Velocity requires `modern player information forwarding`
- BungeeCord requires BungeeGuard 1.4.0 or newer on the proxy and every backend server
- BungeeCord with Spigot 1.20.1 also requires ProtocolLib 5.4.0 on the backend server
- Backend servers must not accept direct connections from the internet. In addition to verifying forwarded identities, restrict access with a firewall or private bind address

## 📋 Requirements

- NCL Skins on the clients of players who change their appearance
- Java 17 or newer for the plugin bytecode. The selected server or proxy may require a newer Java version
- Online mode for a standalone server, or correctly configured and verified identity forwarding through a supported proxy

## ⚖️ License

NCL Skins Plugin is distributed under the **GPL-3.0-only** license.
