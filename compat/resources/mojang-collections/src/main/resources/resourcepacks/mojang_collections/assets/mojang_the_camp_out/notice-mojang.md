# Camp Out Community Challenge

- Copyright owner and author attribution: Mojang Studios
- Official publication: https://www.minecraft.net/en-us/article/camp-out-community-challenge
- Observed retrieval: 2026-10-14 UTC (2026-10-15 Europe/Moscow)

Big Foot (`big_foot`):

- Primary download: https://minecraft.net/content/dam/minecraftnet/games/minecraft/software/FallGameDrop_26_BigfootSkinpack.zip
- Effective download: https://www.minecraft.net/content/dam/minecraftnet/games/minecraft/software/FallGameDrop_26_BigfootSkinpack.zip
- Archive SHA-256: `69bc6955fad1d9225eac3c6abe57f54479cce537a78c9419bdc2144f4edc5aec`
- Original ZIP entry: `Content/skin_pack/Bigfoot.png`
- Included original: `textures/entity/player/slim/big_foot.png`
- PNG SHA-256: `72d5dcde3bb9fc775b4ed72911564b760c2865be383c8ded6d38e258b85efb39`
- Model: Slim only

Mushroom Forager (`mushroom_forager`):

- Primary download: https://minecraft.net/content/dam/minecraftnet/games/minecraft/software/FallGameDrop_26_MushroomForagerSkinpack.zip
- Effective download: https://www.minecraft.net/content/dam/minecraftnet/games/minecraft/software/FallGameDrop_26_MushroomForagerSkinpack.zip
- Archive SHA-256: `83e25b20c3bb13185b7fc0d35fb66ca13233c0f9e5b10d4e4b12262d29e6ec85`
- Original ZIP entry: `Content/skin_pack/Mushroom_Forager.png`
- Included original: `textures/entity/player/wide/mushroom_forager.png`
- PNG SHA-256: `d34d94e146925c8a79e319279df2b31407e853eaf475a4e51d044b093fbf0852`
- Model: Classic only

Both unchanged Java originals are 64x64, 8-bit RGBA PNGs. Model selection is a
technical inference from the original UV layout, not an official per-file model
declaration. The ZIP metadata lists both geometries with empty texture references
and does not map either geometry to its PNG. In the four classic-only UV regions
(54,20)-(56,32), (46,52)-(48,64), (50,16)-(52,20), and (42,48)-(44,52), using
exclusive upper bounds, all 64 sampled alpha values are zero for Big Foot and
255 for Mushroom Forager. No alternate variants or converted artwork are included.

The source archives contain no explicit redistribution license. This notice records
provenance and does not grant rights to the artwork or license it under NaoCraftLab's
GPL. Public download and attribution do not replace the Minecraft EULA or Usage
Guidelines. Separate owner legal/takedown risk disposition and publication
authorization remain required. See the pack-root `NOTICE-MOJANG.md`.
