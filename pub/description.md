# 🎨 NCL Skins

<a href="https://modrinth.com/mod/nclskins-plugin"><img src="https://img.shields.io/static/v1?label=Modrinth&amp;message=NCL%20Skins%20Plugin&amp;color=1bd96a&amp;logo=modrinth&amp;logoColor=white&amp;style=for-the-badge" alt="NCL Skins Plugin on Modrinth" /></a> <a href="https://www.curseforge.com/minecraft/bukkit-plugins/nclskins-plugin"> <img src="https://img.shields.io/static/v1?label=CurseForge&amp;message=NCL%20Skins%20Plugin&amp;color=fb4e44&amp;logo=curseforge&amp;logoColor=white&amp;style=for-the-badge" alt="NCL Skins Plugin on CurseForge" /> </a>

Managing your appearance in Minecraft should feel as natural as everything else in the game. NCL Skins brings skins, Classic or Slim arms, capes, second-layer settings, and saved looks together in one built-in gallery.

Create complete looks and switch between them with one click. Each look remembers its skin, Classic or Slim model, selected Cape or Elytra, and the visibility of every Second layer part.

Your library is shared across every Minecraft instance on this computer that uses the same account.

## 🧩 Build your look in-game

- Preview the complete look before saving
- Rotate and zoom the player model while editing
- Choose a Classic or Slim model
- Choose capes from your Minecraft account, import your own from files, or use capes from resource packs, and switch between cape and elytra previews
- Configure the hat, jacket, sleeves, and pants legs separately for each look
- Search, edit, duplicate, and delete looks in **My looks**

## 🔎 Discover and import skins

Import a skin from a file or direct link, or find a player's current skin by name or UUID. NCL Skins automatically detects whether it uses the Classic or Slim model, and you can change it in the editor when needed.

Bring over saved looks from Minecraft Launcher, Modrinth App, CurseForge App, Prism Launcher, Skin Shuffle, SimpleSkinSwapper / Skin Swapper, and Quick Skin. Before importing, NCL Skins separates new skins from ones already in your catalog so you can select only the ones you want.

The built-in Catalog includes the standard Minecraft characters and optional collections of official Mojang event skins. Resource packs can add their own skin collections.

## 🦸 Custom capes and collections

Import your own capes from files, give them names, and save them in your looks. Browse capes from active resource packs, including the bundled Mojang cape collections. Once you save a cape in a look, it stays available even if you disable its resource pack.

Search the cape catalog by name and filter capes by whether they include an elytra texture. The grid adjusts to fit your window.

Custom capes and resource pack capes are visible only to you and do not replace the official cape on your Minecraft profile. Installing the server mod or plugin does not make these capes visible to other players.

## 🎛️ Appearance providers

Choose where your skins and capes come from. Configure **Offline** and **Minecraft** separately for each: enable the providers you want, change their priority, and mix your local appearance with the skin or cape from your Minecraft profile.

Turning off Minecraft for both skins and capes hides Minecraft profile skins and capes for you and other players from your view. These settings only affect what you see.

## ✨ Check skin compatibility

The mod recognizes extra skin features for Ears, Fresh Moves, and Just Expressions and shows in advance how each skin will work in your current setup. Compatibility badges appear in the Catalog, **My looks**, the editor, and before import.

If an active mod or resource pack uses the skin's pixels in a conflicting way, NCL Skins explains the cause. You can also hide incompatible skins separately in the Catalog and **My looks** in Settings.

With My Totem Doll set to `Holding Player`, your totem’s texture updates as soon as you switch looks.

## 🌐 Client and server use

On the client side, only the player changing their appearance needs the mod. Without a server-side component, the official skin or cape change will still succeed, but other players may need to reconnect, or the changed player may need to be reloaded by the server, before the result becomes visible.

To show confirmed appearance changes to everyone else without reconnecting, install the matching version of NCL Skins on a modded server or [NCL Skins Plugin](https://www.curseforge.com/minecraft/bukkit-plugins/nclskins-plugin) on a supported server and proxy, if one is used. The server sends the refreshed skin and cape to every player, including players who do not use the mod.

## 📴 Local-first and offline support

Your gallery remains available even when Minecraft Services are temporarily unavailable. You can create, edit, and preview looks, apply your appearance locally, and keep playing. If you choose a look while your game session is unavailable, NCL Skins remembers your choice and syncs your skin and official cape with your Minecraft profile once your session and Minecraft services are available again.

If Minecraft temporarily limits skin or cape changes, NCL Skins keeps your latest choice, shows the waiting progress on the affected action, and applies it automatically when the limit ends.

Data is isolated by account but shared by every Minecraft instance launched with the same account on this computer. You will not have to recreate your looks for every Minecraft setup.

## 🕹️ Part of the familiar Minecraft interface

NCL Skins integrates with existing game screens, so managing your skins does not require a separate launcher or website:

- Click your player in the Main Menu or Game Menu to open **My looks**
- The **Appearance...** button in Settings opens the same gallery, while **Main Hand** is available under **Accessibility Settings**
- Place the player preview on the left or right, or turn it off, with separate settings for the title screen and pause menu
- While you are in a world, the editor can show the combined result of compatible mods and resource packs that change the player model
- Set keybindings to open **My looks**, the active look editor, **Providers**, the skin catalog, and skin import while playing; no keys are assigned by default
- Use FancyMenu actions to open these same five screens from your custom menus

## 📦 Create your own skin and cape collections

Collections are distributed as regular Java Edition resource packs. For a minimal collection, give it a unique ID, place the PNG skins in the `wide` and `slim` folders, and name the files as follows:

```text
<pack-root>/
├── pack.mcmeta
└── assets/<collection-id>/textures/entity/player/
    ├── wide/<skin-id>.png
    └── slim/<skin-id>.png
```

The `wide` folder is for the Classic model, while `slim` is for the Slim model. Matching file names in both folders create two variants of the same skin. Collection IDs and file names must use lowercase Latin letters, numbers, hyphens, and underscores. If no localization is provided, NCL Skins automatically turns these IDs into display names.

To give a collection more detail, add standard resource-pack localization files under `assets/<any-namespace>/lang/<locale>.json`. Each collection and skin can have a localized name, description, and author credits using the following keys:

```text
nclskins.<collection-id>.name
nclskins.<collection-id>.description
nclskins.<collection-id>.authors
nclskins.<collection-id>.skin.<skin-id>.name
nclskins.<collection-id>.skin.<skin-id>.description
nclskins.<collection-id>.skin.<skin-id>.authors
```

For cape collections, place static 64×32 PNG files at the path below. No separate manifest is needed. Animated capes and other image sizes are not supported. If the cape includes an elytra texture, it must be part of the same PNG.

```text
<pack-root>/assets/<collection-id>/textures/entity/cape/<cape-id>.png
```

The same ID and collection localization rules apply to capes. To add a name, description, and author credits for an individual cape, use these keys:

```text
nclskins.<collection-id>.cape.<cape-id>.name
nclskins.<collection-id>.cape.<cape-id>.description
nclskins.<collection-id>.cape.<cape-id>.authors
```

When publishing a collection on Modrinth or CurseForge, we recommend adding `x NCL Skins` to the resource pack's title. This makes compatible collections easier to find through platform search.

## ⚖️ License

- You may include NCL Skins in modpacks and install it on your servers
- You may create and distribute forks under the **GPL-3.0-only** license. Any distributed fork must remain under this license, and recipients must be given access to its complete corresponding source code
- Minecraft event skins and Mojang capes belong to Mojang Studios and/or Microsoft and are not distributed by NaoCraftLab under the GPL
