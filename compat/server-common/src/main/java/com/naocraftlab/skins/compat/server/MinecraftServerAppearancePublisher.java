package com.naocraftlab.skins.compat.server;

import com.naocraftlab.skins.server.BatchAppearancePublisher;
import com.naocraftlab.skins.server.BatchPublicationResult;
import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.PublicationRequest;
import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import com.naocraftlab.skins.server.runtime.ServerRefreshPolicy;
import com.naocraftlab.skins.server.vanilla.ConnectionRegistry;
import com.naocraftlab.skins.server.vanilla.LiveProfileTextures;
import com.naocraftlab.skins.server.vanilla.PlatformScheduler;
import com.naocraftlab.skins.server.vanilla.TrackingAccess;
import com.naocraftlab.skins.server.vanilla.VanillaBatchAppearancePublisher;
import com.naocraftlab.skins.server.vanilla.VanillaPublicationPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;


public final class MinecraftServerAppearancePublisher
        implements BatchAppearancePublisher, AutoCloseable {
    private final VanillaBatchAppearancePublisher delegate;

    MinecraftServerAppearancePublisher(
            MinecraftServer server,
            MinecraftServerConnectionRegistry connections,
            ServerRefreshPolicy policy) {
        this(
                server,
                connections,
                new MinecraftProfilePropertyAccess(server),
                new MinecraftServerTrackingAccess(server),
                new MinecraftPlayerInfoTransport(),
                policy);
    }

    MinecraftServerAppearancePublisher(
            MinecraftServer server,
            MinecraftServerConnectionRegistry connections,
            ProfilePropertyAccess nativeProfiles,
            ServerTrackingAccess nativeTracking,
            NativePlayerInfoTransport nativeTransport,
            ServerRefreshPolicy policy) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(connections, "connections");
        Objects.requireNonNull(nativeProfiles, "nativeProfiles");
        Objects.requireNonNull(nativeTracking, "nativeTracking");
        Objects.requireNonNull(nativeTransport, "nativeTransport");
        ServerRefreshPolicy checkedPolicy = Objects.requireNonNull(policy, "policy");

        ConnectionRegistry connectionPort = new ConnectionRegistry() {
            @Override
            public boolean isCurrent(PublicationRequest actor) {
                ServerPlayer player = connections.resolve(actor.connection());
                return player != null && matchesIdentity(player, actor.profile());
            }

            @Override
            public boolean isCurrent(ConnectionKey connection) {
                return connections.resolve(connection) != null;
            }

            @Override
            public List<ConnectionKey> recipients() {
                List<ConnectionKey> result = new ArrayList<>();
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    connections.keyFor(player).ifPresent(result::add);
                }
                return List.copyOf(result);
            }

            @Override
            public boolean isProfileVisible(
                    ConnectionKey recipient,
                    PublicationRequest actor) {


                return connections.resolve(recipient) != null
                        && isCurrent(actor);
            }
        };
        com.naocraftlab.skins.server.vanilla.ProfileAccess profilePort =
                new com.naocraftlab.skins.server.vanilla.ProfileAccess() {
                    @Override
                    public LiveProfileTextures captureCurrent(PublicationRequest actor) {
                        ServerPlayer player = requireCurrent(connections, actor);
                        CurrentProfileTextures current = nativeProfiles.currentTextures(player);
                        return switch (current.status()) {
                            case ACCOUNT_DEFAULT -> LiveProfileTextures.accountDefault();
                            case SIGNED -> LiveProfileTextures.signed(
                                    current.property().orElseThrow());
                            case INVALID -> LiveProfileTextures.invalid();
                        };
                    }

                    @Override
                    public void install(PublicationRequest actor) {
                        ServerPlayer player = requireCurrent(connections, actor);
                        nativeProfiles.installTextures(player, actor.profile().textures());
                    }
                };
        TrackingAccess trackingPort = new TrackingAccess() {
            @Override
            public List<ConnectionKey> snapshotObservers(PublicationRequest actor) {
                ServerPlayer player = requireCurrent(connections, actor);
                Optional<PlayerTracking> handle = nativeTracking.tracking(player);
                if (handle.isEmpty()) {
                    return List.of();
                }
                List<ConnectionKey> result = new ArrayList<>();
                for (ServerPlayer observer : handle.orElseThrow().observers()) {
                    connections.keyFor(observer).ifPresent(result::add);
                }
                return List.copyOf(result);
            }

            @Override
            public boolean untrack(PublicationRequest actor, ConnectionKey observerKey) {
                ServerPlayer player = requireCurrent(connections, actor);
                ServerPlayer observer = connections.resolve(observerKey);
                if (observer == null) {
                    return false;
                }
                Optional<PlayerTracking> handle = nativeTracking.tracking(player);
                if (handle.isEmpty()) {
                    return false;
                }
                PlayerTracking current = handle.orElseThrow();
                boolean observed = current.observers().stream()
                        .anyMatch(candidate -> candidate == observer);
                if (!observed) {
                    return false;
                }
                current.untrack(observer);
                return true;
            }

            @Override
            public void retrack(PublicationRequest actor, ConnectionKey observerKey) {
                ServerPlayer player = connections.resolve(actor.connection());
                ServerPlayer observer = connections.resolve(observerKey);
                if (player == null || observer == null || !matchesIdentity(player, actor.profile())) {
                    return;
                }
                nativeTracking.tracking(player).ifPresent(handle -> handle.retrack(observer));
            }
        };
        com.naocraftlab.skins.server.vanilla.PlayerInfoTransport transportPort =
                new com.naocraftlab.skins.server.vanilla.PlayerInfoTransport() {
                    @Override
                    public void removeProfiles(
                            ConnectionKey recipient,
                            List<PublicationRequest> actors) {
                        nativeTransport.removeProfiles(
                                requireCurrent(connections, recipient),
                                resolveActors(connections, actors));
                    }

                    @Override
                    public void initializeProfiles(
                            ConnectionKey recipient,
                            List<PublicationRequest> actors) {
                        nativeTransport.initializeProfiles(
                                requireCurrent(connections, recipient),
                                resolveActors(connections, actors));
                    }
                };
        PlatformScheduler scheduler = new PlatformScheduler() {
            @Override
            public boolean isPlatformThread() {
                return server.isSameThread();
            }

            @Override
            public void execute(Runnable action) {
                server.execute(Objects.requireNonNull(action, "action"));
            }

            @Override
            public void nextTick(Runnable action) {
                nativeTracking.scheduleNextTick(server, action);
            }

            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public long tickId() {
                return server.getTickCount();
            }
        };
        int reconciliationCapacity = saturatedMultiply(
                Math.max(1, server.getPlayerList().getMaxPlayers()), 4);
        delegate = new VanillaBatchAppearancePublisher(
                connectionPort,
                profilePort,
                trackingPort,
                transportPort,
                scheduler,
                new MinecraftOfficialTextureSignatureVerifier(server),
                new VanillaPublicationPolicy(
                        checkedPolicy.maxBatchActors(),
                        checkedPolicy.maxPacketEntries(),
                        checkedPolicy.maxRecipientProfileDeliveriesPerTick(),
                        checkedPolicy.maxPlatformThreadTimePerTick(),
                        checkedPolicy.maxReconciliationAttempts(),
                        reconciliationCapacity));
    }

    @Override
    public CompletionStage<BatchPublicationResult> publishBatch(
            List<PublicationRequest> requests) {
        return delegate.publishBatch(requests);
    }

    @Override
    public void supersede(ConnectionKey connection) {
        delegate.supersede(connection);
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static ServerPlayer requireCurrent(
            MinecraftServerConnectionRegistry connections,
            PublicationRequest actor) {
        ServerPlayer player = connections.resolve(actor.connection());
        if (player == null || !matchesIdentity(player, actor.profile())) {
            throw new IllegalStateException("Stale actor connection");
        }
        return player;
    }

    private static ServerPlayer requireCurrent(
            MinecraftServerConnectionRegistry connections,
            ConnectionKey connection) {
        ServerPlayer player = connections.resolve(connection);
        if (player == null) {
            throw new IllegalStateException("Stale recipient connection");
        }
        return player;
    }

    private static List<ServerPlayer> resolveActors(
            MinecraftServerConnectionRegistry connections,
            List<PublicationRequest> actors) {
        List<ServerPlayer> result = new ArrayList<>(actors.size());
        for (PublicationRequest actor : actors) {
            result.add(requireCurrent(connections, actor));
        }
        return List.copyOf(result);
    }

    private static boolean matchesIdentity(
            ServerPlayer actor, VerifiedOfficialProfile profile) {
        return actor.getUUID().equals(profile.identity().profileId())
                && actor.getName().getString().equals(profile.identity().profileName());
    }

    private static int saturatedMultiply(int value, int factor) {
        long product = (long) value * factor;
        return product >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) product;
    }
}
