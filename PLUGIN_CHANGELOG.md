## 1.0.0

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

## 1.0.0-beta.3

### Changed

- Replaced technical refresh commands with an internal connection signal
- Improved the reliability of realtime skin updates on Bukkit-family servers

### Removed

- Refresh commands, command help, and autocomplete entries from servers and proxies

## 1.0.0-beta.2

- Initial release of the universal server plugin for supported Bukkit-family servers, Velocity, and BungeeCord
- Refreshes confirmed official skin changes for players in the world and player list without reconnecting
- Supports protected proxy forwarding through Velocity modern forwarding or BungeeGuard
