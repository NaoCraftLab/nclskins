## 1.2.0.1

### Changed

- **Appearance updates across providers**
    - Share appearance changes from your providers, including OptiFine and SkinMC, with other NCL Skins players even when your Minecraft skin and cape have not changed
    - After changing your cape on the provider's website, click `Refresh` in the mod so other players can see the change without reconnecting
    - Each player sees the result according to their own enabled providers and priorities

## 1.0.0.3

### Added

- Support for Minecraft 26.3 on Purpur

## 1.0.0.2

### Added

- Support for Minecraft 26.3 on Paper, Velocity, and BungeeCord

## 1.0.0.1

### Added

- **Universal server plugin for NCL Skins**
    - Install the stable 1.0.0 release without behavior change on supported game servers and proxy networks
    - Refresh a player's skin in the world and player list without reconnecting
- **Reliable realtime appearance refresh**
    - An internal connection signal delivers updates without user commands, help entries, or autocomplete
    - Rapid consecutive changes and game-server updates are handled without losing the latest result
- **Protected proxy support**
    - Accept refreshes only from connections verified by trusted proxy forwarding
    - Use protected forwarding through Velocity modern forwarding or BungeeGuard

## 1.0.0.1-beta.3

### Changed

- Replaced technical refresh commands with an internal connection signal
- Improved the reliability of realtime skin updates on Bukkit-family servers

### Removed

- Refresh commands, command help, and autocomplete entries from servers and proxies

## 1.0.0.1-beta.2

- Initial release of the universal server plugin for supported Bukkit-family servers, Velocity, and BungeeCord
- Refreshes confirmed official skin changes for players in the world and player list without reconnecting
- Supports protected proxy forwarding through Velocity modern forwarding or BungeeGuard
