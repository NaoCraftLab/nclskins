# 🎨 NCL Skins

Managing your appearance in Minecraft should feel as natural as everything else in the game. NCL Skins brings skins, arm models, capes,
outer layers, and saved looks together in one built-in gallery.

Create complete looks and switch between them with one click. Each look remembers its skin, Classic or Slim model, selected Cape or
Elytra, and the visibility of every Second layer part.

Your library is shared across every Minecraft instance on your computer that uses the same account.

## 🧩 Build your look in-game

- Preview the complete look before saving
- Rotate and zoom the player model while editing
- Choose a Classic or Slim model
- Use a cape you own, display it as a Cape or Elytra, or turn it off
- Configure the hat, jacket, sleeves, and pants legs separately for each look
- Search, edit, duplicate, and delete looks in **My looks**

## 🔎 Discover and import skins

Import a skin from a file or direct link, or find a player's current skin by name or UUID. NCL Skins automatically detects whether it
uses the Classic or Slim model, and you can change it in the editor when needed.

Bring over saved looks from Minecraft Launcher, Modrinth App, CurseForge App, Prism Launcher, Skin Shuffle,
SimpleSkinSwapper / Skin Swapper, and Quick Skin. Before importing, NCL Skins shows new skins and those already in your catalog, then lets you
choose only the ones you want.

The built-in Catalog includes the standard Minecraft characters and optional collections of official Mojang event skins. Resource packs
can add their own skin collections.

## 🌐 Client and server use

NCL Skins is required on the client. Installing the matching version on a server is optional: once an appearance change has been
confirmed on your Minecraft profile, the server component refreshes it for other players without requiring you to disconnect and
rejoin.

## 📴 Local-first and offline support

Your gallery remains available even when Minecraft Services are temporarily unavailable. You can create, edit, and preview looks, apply
your appearance locally, and keep playing. NCL Skins can synchronize your official skin and cape when the service becomes available
again.

If Minecraft temporarily limits skin or cape changes, NCL Skins keeps your latest choice, shows the waiting progress on the affected
action, and applies it automatically when the limit ends.

Data is isolated by account but shared by all Minecraft instances launched with the same account on this computer. You will not have to
recreate your looks for every Minecraft setup.

## 🕹️ Part of the familiar Minecraft interface

NCL Skins integrates with existing game screens, so managing your skins does not require a separate launcher or website:

- Click your player in the Main Menu or Game Menu to open **My looks**
- The **Appearance...** button in Settings opens the same gallery, while **Main Hand** is available under **Accessibility Settings**
- Your current appearance is always visible in the menus
- While you are in a world, the editor can show the combined result of compatible mods and resource packs that change the player model

## 📦 Create your own skin collection

Collections are distributed as ordinary Java resource packs. For a minimal collection, give it a unique ID, place the PNG skins in the
`wide` and `slim` folders, and name the files:

```text
<pack-root>/
├── pack.mcmeta
└── assets/<collection-id>/textures/entity/player/
    ├── wide/<skin-id>.png
    └── slim/<skin-id>.png
```

The `wide` folder is for the Classic model, while `slim` is for the Slim model. Matching file names in both folders create two variants
of the same skin. Write collection IDs and file names using lowercase Latin letters, numbers, hyphens, and underscores. If no
localization is provided, NCL Skins automatically turns these IDs into display names.

To give your collection a more polished presentation, add standard resource-pack localization files under
`assets/<any-namespace>/lang/<locale>.json`. Each collection and each skin can have its own localized name, description, and author
credit using the following keys:

```text
nclskins.<collection-id>.name
nclskins.<collection-id>.description
nclskins.<collection-id>.authors
nclskins.<collection-id>.skin.<skin-id>.name
nclskins.<collection-id>.skin.<skin-id>.description
nclskins.<collection-id>.skin.<skin-id>.authors
```

When publishing a collection on Modrinth or CurseForge, we recommend adding `x NCL Skins` to the resource pack's title. This makes
compatible collections easier to find through platform search.

## ⚖️ License

- You may include NCL Skins in modpacks and install it on your servers
- You may create and distribute forks under **GPL-3.0-only**. If you distribute a fork, it must remain under this license and you must
  make its complete corresponding source code available to recipients
- Minecraft event skin assets belong to Mojang Studios and/or Microsoft and are not licensed under the GPL by NaoCraftLab
