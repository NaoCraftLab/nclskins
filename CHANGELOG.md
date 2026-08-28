## 1.0.0-beta.5

### Added

- **Skin compatibility guidance**
    - Compatibility markers in `Catalog`, `My looks`, the look editor, and `Review import` show embedded skin features, with details available by mouse or keyboard
    - Quickly spot skins made for Ears, Fresh Moves, and Just Expressions, as well as warnings about conflicts with active mods and resource packs
- **Hide incompatible skins**
    - Choose independently whether to hide incompatible skins from `Catalog` and incompatible looks from `My looks`
    - Keep the active look visible even when other incompatible looks are hidden

### Fixed

- 64x32 skins are automatically converted to the modern layout before previewing and saving, preventing stray pixels from appearing as facial details in animations

## 1.0.0-beta.4

### Added

- **Full keyboard navigation**
    - Navigate `My looks`, `Catalog`, `Review import`, and cape selection with Tab and the arrow keys
    - Open cards and actions with Enter or Space, including creating a look and confirming deletion
- **Collapse and expand all**
    - Collapse or expand every collection in `Catalog`
    - Collapse or expand every group in `Review import` as well

### Fixed

- Numerous minor UI fixes
- Fixed session checking and offline mode behavior

## 1.0.0-beta.3

### Added

- **Cross-platform update notifications**
    - See new NCL Skins versions directly in the installed mods screen
    - Get only updates compatible with your Minecraft version and mod loader

### Removed

- Technical refresh commands

## 1.0.0-beta.2

### Added

- **NCL Skins Plugin support**
    - See confirmed skin changes without reconnecting on servers that install NCL Skins Plugin

### Changed

- **Improved mod menu presentation**
    - Added useful links and clearer icons across installed mods screens
    - Adapted the mod card for Catalogue and the latest NeoForge menu

### Fixed

- **More reliable live skin updates**
    - Keep rapid skin changes working correctly during disconnects and server shutdown
    - Prevent one failed update from blocking later player updates

## 1.0.0-beta.1

### Added

- **More outer-layer controls**
    - Choose any combination of the body, left arm, and right arm in the look editor

### Changed

- **Smoother delayed updates**
    - Automatically apply your latest skin or cape choice after a temporary Minecraft limit
    - Show waiting progress on the affected action
- **Updated visuals**
    - Refreshed button icons and the NCL Skins icon
    - Made selected capes and imported looks easier to see

### Fixed

- **Session and server refresh**
    - Hide `Refresh session` when Minecraft must be restarted
    - Fixed live skin updates after opening a singleplayer world to LAN

## 1.0.0-alpha.2

### Added

- **Imports from launchers and other mods**
  - Preview and import saved looks from Minecraft Launcher, CurseForge App, Modrinth App, Prism Launcher, Skin Shuffle, Simple/SkinSwapper, and Quick Skin
  - Choose a different app or instance folder if automatic discovery does not find it
  - Review new and duplicate looks before importing, then import only the entries you select
- **Automatic skin model detection**
  - Imports from files, direct links, player names, and UUIDs automatically detect Classic or Slim from the skin itself
  - Imported looks preserve their specified model when available and detect it automatically when missing
  - You can still switch the model manually in the look editor when needed
- **Preview support for compatible mods**
  - In the in-world look editor, you can see Ears features with their original transparency, 3D Skin Layers geometry, and Fresh Moves/Just Expressions animations together
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
    - When you are already in a world, the editor shows the combined result of active mods and resource packs that change the player model, such as 3D Skin Layers, Fresh Moves, or Just Expressions
- **My looks gallery**
    - Build complete looks from a skin, model, cape, and outer layers, then switch between them with one click
    - Search, edit, duplicate, and delete your looks
    - Show everyone on the server your new look without rejoining the world (requires NCL Skins on the server)
- **Skin catalog and resource pack collections**
    - Choose from standard Minecraft characters, Mojang event skins, and skins from your active resource packs
    - Find the right skin quickly with search and the Classic/Slim filter
    - Add new collections through regular Java resource packs and they will appear in the catalog automatically
- **Offline support and Minecraft sync**
    - Change your look even when Minecraft Services are unavailable and see it immediately without reconnecting (other players will not see offline changes)
    - When the connection returns, the mod syncs your selected skin and cape with your Minecraft profile
- **One library across all game instances**
    - Your looks, imported skins, and mod state are available across all game instances at the same time
    - Data stays separate for each account used to launch the game
- **Built into Minecraft**
    - Open `My looks` by clicking your character on the Main Menu or Game Menu
    - `Skin Customization...` now opens `My looks`, while `Main Hand` has moved to Accessibility Settings
