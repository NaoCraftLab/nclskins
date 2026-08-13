package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.RefreshSubmission;
import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import org.bukkit.entity.Player;


interface BukkitRefreshEngine extends AutoCloseable {
    void connected(Player player);

    void disconnected(Player player);

    RefreshSubmission request(Player player);

    @Override
    void close();

    @FunctionalInterface
    interface PublicationListener {
        void published(Player player, VerifiedOfficialProfile profile);
    }
}
