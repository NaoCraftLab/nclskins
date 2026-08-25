package com.naocraftlab.skins.server.plugin.bukkit;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.naocraftlab.skins.server.SignedTexturesProperty;
import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;

final class PaperProfilePublicationBackend implements BukkitPublicationBackend {
    private static final String TEXTURES = "textures";
    private static final Publication COMPLETE = new Publication() {
        @Override
        public List<Player> observers() {
            return List.of();
        }

        @Override
        public void untrack(Player observer) {
        }

        @Override
        public void sendPlayerInfo(Player observer) {
        }

        @Override
        public void retrack(Player observer) {
        }

        @Override
        public boolean isTracking(Player observer) {
            return true;
        }
    };

    @Override
    public Publication installAndSnapshot(Player actor, VerifiedOfficialProfile profile) {
        Player checkedActor = Objects.requireNonNull(actor, "actor");
        PlayerProfile replacement = checkedActor.getPlayerProfile();
        replacement.removeProperty(TEXTURES);
        profile.textures().ifPresent(textures -> replacement.setProperty(property(textures)));
        checkedActor.setPlayerProfile(replacement);
        return COMPLETE;
    }

    private static ProfileProperty property(SignedTexturesProperty textures) {
        return new ProfileProperty(TEXTURES, textures.value(), textures.signature());
    }
}
