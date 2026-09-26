package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;

final class PaperProfilePublicationBackend implements BukkitPublicationBackend {
    private final PaperPublicationBinding binding;

    private PaperProfilePublicationBackend(PaperPublicationBinding binding) {
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    static PaperProfilePublicationBackend resolve(
            ClassLoader classLoader,
            String craftServerPackage,
            String authlibFamily) throws ReflectiveOperationException {
        return new PaperProfilePublicationBackend(
                PaperPublicationBinding.resolve(classLoader, craftServerPackage, authlibFamily));
    }

    @Override
    public Publication installAndSnapshot(
            Plugin plugin, Player actor, VerifiedOfficialProfile profile) {
        Objects.requireNonNull(plugin, "plugin");
        Player checkedActor = Objects.requireNonNull(actor, "actor");
        VerifiedOfficialProfile checkedProfile = Objects.requireNonNull(profile, "profile");
        PaperPublicationBinding.Actor installed = binding.install(checkedActor, checkedProfile);
        List<Player> observers = Bukkit.getOnlinePlayers().stream()
                .filter(observer -> observer != checkedActor
                        && observer.isOnline()
                        && observer.canSee(checkedActor))
                .map(Player.class::cast)
                .toList();
        return new PaperPublication(checkedActor, installed, observers);
    }

    private static final class PaperPublication implements Publication {
        private final Player actor;
        private final PaperPublicationBinding.Actor installed;
        private final List<Player> observers;

        private PaperPublication(Player actor, PaperPublicationBinding.Actor installed, List<Player> observers) {
            this.actor = Objects.requireNonNull(actor, "actor");
            this.installed = Objects.requireNonNull(installed, "installed");
            this.observers = List.copyOf(observers);
        }

        @Override
        public List<Player> observers() {
            return observers;
        }

        @Override
        public void untrack(Player observer) {
            installed.untrack(observer);
        }

        @Override
        public void sendPlayerInfo(Player observer) {
        }

        @Override
        public void retrack(Player observer) {
            installed.retrack(observer);
        }

        @Override
        public boolean isTracking(Player observer) {
            return observer.canSee(actor);
        }

    }
}
