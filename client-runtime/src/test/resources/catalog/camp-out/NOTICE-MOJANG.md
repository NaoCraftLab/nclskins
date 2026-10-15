# Camp Out test fixtures — Mojang artwork

Test-only copies of the unchanged original Java skin PNGs by Mojang Studios.
Minecraft and this artwork belong to Mojang Studios and/or Microsoft. NaoCraftLab
makes no GPL license grant for these PNGs. These provenance facts do not grant
redistribution rights or replace the Minecraft EULA or Usage Guidelines. No owner
legal/takedown risk disposition or publication authorization is asserted here.

Primary publication:
https://www.minecraft.net/en-us/article/camp-out-community-challenge

Observed retrieval: 2026-10-15 (Europe/Moscow).

Big Foot:
  Source: https://minecraft.net/content/dam/minecraftnet/games/minecraft/software/FallGameDrop_26_BigfootSkinpack.zip
  ZIP SHA-256: 69bc6955fad1d9225eac3c6abe57f54479cce537a78c9419bdc2144f4edc5aec
  ZIP entry: Content/skin_pack/Bigfoot.png
  Fixture: assets/mojang_the_camp_out/textures/entity/player/slim/big_foot.png
  PNG SHA-256: 72d5dcde3bb9fc775b4ed72911564b760c2865be383c8ded6d38e258b85efb39
  Decoded row-major RGBA SHA-256: 5f9e5b2b02931163585b1d8bff4a9ab98c2a542530a3a821867449c18ac25f3e

Mushroom Forager:
  Source: https://minecraft.net/content/dam/minecraftnet/games/minecraft/software/FallGameDrop_26_MushroomForagerSkinpack.zip
  ZIP SHA-256: 83e25b20c3bb13185b7fc0d35fb66ca13233c0f9e5b10d4e4b12262d29e6ec85
  ZIP entry: Content/skin_pack/Mushroom_Forager.png
  Fixture: assets/mojang_the_camp_out/textures/entity/player/wide/mushroom_forager.png
  PNG SHA-256: d34d94e146925c8a79e319279df2b31407e853eaf475a4e51d044b093fbf0852
  Decoded row-major RGBA SHA-256: 4d1856307cab54563827178bf5c8dad554bdd7034a6d831fa1ab2b211332261b

Both are 64x64, 8-bit RGBA. Slim/Classic is a technical inference from the
original UV layout, not an unambiguous per-file declaration in the ZIP metadata.
The 64 classic-only UV alpha values are all zero for Big Foot and all 255 for
Mushroom Forager. The fixture path determines the catalog model; runtime does not
add alternate variants or infer the model afresh. The decoded-pixel checksums
above were independently obtained before these product-path tests.

These fixtures characterize the existing baseline pipeline. They are not proof
that the production optional pack includes Camp Out, that native rendering was
accepted, or that a release is authorized.
