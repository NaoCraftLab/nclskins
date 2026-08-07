## 1.0.0-alpha.2

### Added

- **Imports from launchers and other mods**
  - Preview and import saved skins and looks from Minecraft Launcher, CurseForge App, Modrinth App, and Prism Launcher
  - Import Skin Shuffle looks, choose what to add, and review duplicates before saving
- **Automatic skin model detection**
  - Imports from files, direct links, player names, and UUIDs now choose Classic or Slim from the skin itself
  - You can still switch the model manually in the look editor

### Fixed

- Capes in the in-world look editor now follow player model animations provided by compatible renderer mods
- Worldless previews no longer run third-party player layers, while the in-world editor gives compatible renderer mods an isolated full
  player context without freezing the real player pose
- Preserved embedded skin alpha data so Ears features keep their transparency instead of rendering black
- Native skins now finish loading before in-world renderer layers or the local player use them, so 3D Skin Layers can build the
  selected look correctly
- Fresh Moves idle animations keep moving in the paused in-world look editor without reusing a frozen world pose or advancing the world
- Empty player-model anchors from renderer packs no longer crash Ears features, including when Fresh Moves is active
- A broken third-party layer is skipped without removing the rest of the in-world preview, while a broader renderer failure safely
  falls back to vanilla for that editor session
- Worldless previews now zoom around their center and render the player, cape, and elytra with one shared rotation and depth
- Static previews now use a shared vanilla model pool so renderer packs cannot bake a paused world pose into menus or gallery cards
- Gallery skins and cape cards on 26.x now keep independent render targets instead of repeating the last visible preset
- Pending preset skins now stay empty until their own native texture is ready instead of briefly showing the current player skin
- Worldless capes on 26.x now use their vanilla attachment pose and remain fixed to the player while rotating and zooming
- Draft cape changes now appear immediately in both cape and elytra modes of the in-world editor

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
