package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;

public interface BukkitPublicationBackend {
    Publication installAndSnapshot(
            Plugin plugin, Player actor, VerifiedOfficialProfile profile);

    interface Publication {
        List<Player> observers();

        void untrack(Player observer);

        void sendPlayerInfo(Player observer);

        void retrack(Player observer);

        boolean isTracking(Player observer);
    }
}
