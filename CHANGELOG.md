## 1.0.0-alpha.2

### Added

- **Imports from launchers and other mods**
  - Preview and import saved looks from Minecraft Launcher, CurseForge App, Modrinth App, Prism Launcher, Skin Shuffle,
    Simple/SkinSwapper, and Quick Skin
  - Choose a different app or instance folder when automatic discovery misses it
  - Review new and duplicate looks, then import only the entries you select
- **Automatic skin model detection**
  - Imports from files, direct links, player names, and UUIDs choose Classic or Slim from the skin itself
  - Imported looks preserve their saved model when available and detect it when missing
  - You can still switch the model manually in the look editor
- **Compatible look previews**
  - View Ears features with their original transparency, 3D Skin Layers geometry, and Fresh Moves animations together in the in-world
    look editor
  - Capes and Elytra follow the preview model and update immediately while you edit the look
  - Worldless menus and galleries use stable vanilla previews, while rotation, tilt, and zoom stay centered
- **Configuration screen**
  - Open NCL Skins settings from Mod Menu or the installed mods screen
  - Choose where the player preview appears and where the mod stores its data
  - Server owners can enable live skin updates, allow trusted proxy connections, and limit profile checks

## 1.0.0-alpha.1

### Added

- **Skin imports**
    - Import skins from a file
    - Find player skins by name or UUID
    - Add skins from a direct link
- **Look editor with preview**
    - See the result right away: Classic or Slim, cape or elytra, and every outer layer
    - Rotate and zoom the model, then save only when your look is ready
    - When you are already in a world, the editor shows the combined result of active mods and resource packs that change the player
      model, such as 3D Skin Layers, Fresh Moves, or Just Expressions
- **My looks gallery**
    - Build complete looks from a skin, model, cape, and outer layers, then switch between them with one click
    - Search, edit, duplicate, and delete your looks
    - Show everyone on the server your new look without rejoining the world (requires NCL Skins on the server)
- **Skin catalog and resource pack collections**
    - Choose from standard Minecraft characters, Mojang event skins, and skins from your active resource packs
    - Find the right skin quickly with search and the Classic/Slim filter
    - Add new collections through regular Java resource packs and they will appear in the catalog automatically
- **Offline support and Minecraft sync**
    - Change your look even when Minecraft Services are unavailable and see it immediately without reconnecting (other players will not
      see offline changes)
    - When the connection returns, the mod syncs your selected skin and cape with your Minecraft profile
- **One library across all game instances**
    - Your looks, imported skins, and mod state are available across all game instances at the same time
    - Data stays separate for each account used to launch the game
- **Built into Minecraft**
    - Open `My looks` by clicking your character on the Main Menu or Game Menu
    - `Skin Customization...` now opens `My looks`, while `Main Hand` has moved to Accessibility Settings
