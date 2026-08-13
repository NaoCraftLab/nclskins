package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.core.config.ServerConfiguration;
import com.naocraftlab.skins.server.Admission;
import com.naocraftlab.skins.server.BatchAppearancePublisher;
import com.naocraftlab.skins.server.BatchPublicationResult;
import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.ConnectionSnapshot;
import com.naocraftlab.skins.server.IdentityAssurance;
import com.naocraftlab.skins.server.OfficialTextureSignatureVerifier;
import com.naocraftlab.skins.server.PublicationOutcome;
import com.naocraftlab.skins.server.PublicationRequest;
import com.naocraftlab.skins.server.RefreshResult;
import com.naocraftlab.skins.server.RefreshSubmission;
import com.naocraftlab.skins.server.ServerPlayerIdentity;
import com.naocraftlab.skins.server.SignedTexturesProperty;
import com.naocraftlab.skins.server.TextureAppearance;
import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import com.naocraftlab.skins.server.runtime.OfficialProfileResolutionService;
import com.naocraftlab.skins.server.runtime.OfficialSessionProfileClient;
import com.naocraftlab.skins.server.runtime.OfficialTextureAppearanceParser;
import com.naocraftlab.skins.server.runtime.ServerAppearanceRefreshCoordinator;
import com.naocraftlab.skins.server.runtime.ServerAppearanceRefreshService;
import com.naocraftlab.skins.server.runtime.ServerRefreshPolicy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import java.util.logging.Logger;


final class ReflectiveBukkitRefreshEngine implements BukkitRefreshEngine {
    private final Object lock = new Object();
    private final BukkitExecution execution;
    private final ReflectiveNativeAccess nativeAccess;
    private final PublicationListener listener;
    private final Logger logger;
    private final Map<UUID, LiveConnection> connections = new LinkedHashMap<>();
    private final NativePublisher publisher;
    private final ServerAppearanceRefreshService service;
    private long nextGeneration;
    private boolean closed;

    ReflectiveBukkitRefreshEngine(
            JavaPlugin plugin,
            ServerConfiguration configuration,
            boolean regionized,
            boolean legacyMapped,
            PublicationListener listener) {
        Objects.requireNonNull(plugin, "plugin");
        ServerConfiguration.RealtimeRefresh refresh = Objects.requireNonNull(
                configuration, "configuration").realtimeRefresh();
        this.listener = Objects.requireNonNull(listener, "listener");
        logger = plugin.getLogger();
        execution = new BukkitExecution(plugin, regionized);
        nativeAccess = new ReflectiveNativeAccess(
                plugin.getClass().getClassLoader(), legacyMapped);
        nativeAccess.verify();
        ServerRefreshPolicy defaults = ServerRefreshPolicy.defaults(
                refresh.trustedProxyForwarding(), Bukkit.getMaxPlayers());
        ServerRefreshPolicy policy = new ServerRefreshPolicy(
                refresh.trustedProxyForwarding(),
                defaults.maxPendingConnections(),
                refresh.maxConcurrentLookups(),
                refresh.lookupRatePerSecond(),
                refresh.lookupBurst(),
                defaults.attemptOffsets(),
                defaults.maxQueueAge(),
                defaults.lookupCycleDeadline(),
                defaults.independentCycleCooldown(),
                defaults.batchWindow(),
                defaults.maxBatchActors(),
                defaults.maxReconciliationAttempts(),
                defaults.maxPacketEntries(),
                defaults.maxRecipientProfileDeliveriesPerTick(),
                defaults.maxPlatformThreadTimePerTick());
        publisher = new NativePublisher();
        OfficialTextureSignatureVerifier verifier = nativeAccess::verifyTextures;
        OfficialProfileResolutionService resolver = new OfficialProfileResolutionService(
                new OfficialSessionProfileClient(), verifier);
        service = new ServerAppearanceRefreshService(
                policy, new ServerAppearanceRefreshCoordinator(resolver, publisher, policy));
    }

    @Override
    public void connected(Player player) {
        Objects.requireNonNull(player, "player");
        ConnectionKey superseded = null;
        synchronized (lock) {
            if (closed) {
                return;
            }
            LiveConnection current = connections.get(player.getUniqueId());
            if (current != null && current.player == player
                    && current.name.equals(player.getName())) {
                return;
            }
            if (nextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Connection generation exhausted");
            }
            if (current != null) {
                superseded = current.key;
            }
            connections.put(player.getUniqueId(), new LiveConnection(
                    player,
                    player.getName(),
                    new ConnectionKey(player.getUniqueId(), ++nextGeneration),
                    assurance()));
        }
        if (superseded != null) {
            service.disconnected(superseded);
        }
    }

    @Override
    public void disconnected(Player player) {
        ConnectionKey removed = null;
        synchronized (lock) {
            LiveConnection current = connections.get(player.getUniqueId());
            if (current != null && current.player == player) {
                connections.remove(player.getUniqueId());
                removed = current.key;
            }
        }
        if (removed != null) {
            service.disconnected(removed);
        }
    }

    @Override
    public RefreshSubmission request(Player player) {
        connected(player);
        LiveConnection connection;
        synchronized (lock) {
            connection = connections.get(player.getUniqueId());
        }
        if (connection == null || connection.player != player) {
            return new RefreshSubmission(Admission.INELIGIBLE,
                    CompletableFuture.completedFuture(RefreshResult.INELIGIBLE));
        }
        return service.request(new ConnectionSnapshot(
                connection.key, connection.name, connection.assurance));
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            connections.clear();
        }
        service.close();
        publisher.close();
    }

    private IdentityAssurance assurance() {
        if (Bukkit.getOnlineMode()) {
            return IdentityAssurance.ONLINE;
        }
        return ProxyConnectionAssurance.assured(true)
                ? IdentityAssurance.TRUSTED_PROXY : IdentityAssurance.OFFLINE;
    }

    private LiveConnection resolve(ConnectionKey key) {
        synchronized (lock) {
            LiveConnection result = connections.get(key.profileId());
            return result != null && result.key.equals(key) ? result : null;
        }
    }

    private final class NativePublisher implements BatchAppearancePublisher, AutoCloseable {
        private boolean publisherClosed;

        @Override
        public CompletionStage<BatchPublicationResult> publishBatch(
                List<PublicationRequest> requests) {
            if (publisherClosed) {
                return CompletableFuture.completedFuture(
                        BatchPublicationResult.all(requests, PublicationOutcome.FAILED));
            }
            Map<ConnectionKey, PublicationOutcome> outcomes = new LinkedHashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (PublicationRequest request : requests) {
                CompletableFuture<Void> future = publishOne(request).handle((updated, failure) -> {
                    if (failure != null) {
                        logger.log(Level.WARNING,
                                "NCL refresh native publication failed", failure);
                    }
                    synchronized (outcomes) {
                        outcomes.put(request.connection(), failure == null && updated
                                ? PublicationOutcome.UPDATED
                                : failure == null ? PublicationOutcome.STALE
                                : PublicationOutcome.FAILED);
                    }
                    return null;
                });
                futures.add(future);
            }
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> BatchPublicationResult.of(outcomes));
        }

        private CompletableFuture<Boolean> publishOne(PublicationRequest request) {
            LiveConnection actor = resolve(request.connection());
            if (actor == null || !actor.name.equals(request.profile().identity().profileName())) {
                return CompletableFuture.completedFuture(false);
            }
            NativePublication publication = new NativePublication(actor, request);
            return execution.player(actor.player, publication::installAndSnapshot)
                    .thenCompose(ignored -> publication.refreshObservers())
                    .thenApply(ignored -> {
                        LiveConnection current = resolve(request.connection());
                        if (current == null) {
                            return false;
                        }
                        listener.published(current.player, request.profile());
                        return true;
                    });
        }

        @Override
        public void supersede(ConnectionKey connection) {
        }

        @Override
        public void close() {
            publisherClosed = true;
        }
    }

    private final class NativePublication {
        private final LiveConnection actor;
        private final PublicationRequest request;
        private ReflectiveNativeAccess.Tracker tracker;
        private List<Player> observers = List.of();

        private NativePublication(LiveConnection actor, PublicationRequest request) {
            this.actor = actor;
            this.request = request;
        }

        private void installAndSnapshot() {
            if (resolve(request.connection()) != actor || !actor.player.isOnline()) {
                throw new IllegalStateException("Stale actor connection");
            }
            nativeAccess.installProfile(actor.player, request.profile());
            tracker = nativeAccess.tracker(actor.player);
            observers = tracker.observers().stream()
                    .filter(observer -> observer != actor.player && observer.isOnline())
                    .toList();
        }

        private CompletableFuture<Void> refreshObservers() {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (Player observer : observers) {
                chain = chain.thenCompose(ignored -> execution.player(observer,
                        () -> BukkitObserverPublication.refresh(
                                () -> tracker.untrack(observer),
                                () -> nativeAccess.refreshPlayerInfo(observer, actor.player),
                                () -> tracker.retrack(observer),
                                () -> tracker.isTracking(observer))));
            }
            return chain;
        }

    }

    private static final class LiveConnection {
        private final Player player;
        private final String name;
        private final ConnectionKey key;
        private final IdentityAssurance assurance;

        private LiveConnection(
                Player player, String name, ConnectionKey key, IdentityAssurance assurance) {
            this.player = player;
            this.name = name;
            this.key = key;
            this.assurance = assurance;
        }
    }

    private static final class ReflectiveNativeAccess {
        private static final String TEXTURES = "textures";
        private final ClassLoader classLoader;
        private final boolean legacyMapped;

        private ReflectiveNativeAccess(ClassLoader classLoader, boolean legacyMapped) {
            this.classLoader = classLoader;
            this.legacyMapped = legacyMapped;
        }

        private void verify() {
            try {
                Class<?> gameProfile = Class.forName(
                        "com.mojang.authlib.GameProfile", false, classLoader);
                Class<?> property = Class.forName(
                        "com.mojang.authlib.properties.Property", false, classLoader);
                property.getConstructor(String.class, String.class, String.class);
                String serverPlayerName = legacyMapped
                        ? "net.minecraft.server.level.EntityPlayer"
                        : "net.minecraft.server.level.ServerPlayer";
                Class<?> serverPlayer = Class.forName(serverPlayerName, false, classLoader);
                Class<?> profileOwner = legacyMapped
                        ? Class.forName(Bukkit.getServer().getClass().getPackageName()
                        + ".entity.CraftPlayer", false, classLoader)
                        : serverPlayer;
                Method profileGetter = method(profileOwner,
                        legacyMapped ? List.of("getProfile") : List.of("getGameProfile"), 0)
                        .orElseThrow();
                if (profileGetter.getReturnType() != gameProfile) {
                    throw new NoSuchMethodException("ServerPlayer#getGameProfile descriptor");
                }
                Class<?> remove = Class.forName(
                        "net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket",
                        false, classLoader);
                remove.getConstructor(List.class);
                Class<?> update = Class.forName(
                        "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket",
                        false, classLoader);
                Method initialize = playerInfoInitializer(update);
                if (!Modifier.isStatic(initialize.getModifiers())) {
                    throw new NoSuchMethodException("Player info initialize factory is not static");
                }
                sessionService(invoke(Bukkit.getServer(), "getServer"));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Exact NMS publication ABI is unavailable", failure);
            }
        }

        private Optional<TextureAppearance> verifyTextures(
                SignedTexturesProperty textures, ServerPlayerIdentity identity) {
            try {
                Object property = property(textures);
                Object minecraftServer = invoke(Bukkit.getServer(), "getServer");
                Object sessionService = sessionService(minecraftServer);
                Method secure = method(sessionService.getClass(),
                        List.of("getSecurePropertyValue"), 1).orElseThrow();
                String verified = (String) secure.invoke(sessionService, property);
                if (!textures.value().equals(verified)) {
                    return Optional.empty();
                }
                return OfficialTextureAppearanceParser.parseVerified(verified, identity);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return Optional.empty();
            }
        }

        private void installProfile(Player player, VerifiedOfficialProfile profile) {
            try {
                Object handle = handle(player);
                Object current = legacyMapped
                        ? invoke(player, "getProfile")
                        : invoke(handle, "getGameProfile");
                Object replacementProperties = replacementProperties(
                        properties(current), profile.textures());
                if (legacyMapped) {
                    return;
                }
                Object replacement = replacementProfile(current, replacementProperties);
                Field field = fieldAssignable(handle.getClass(), current.getClass(), "gameProfile");
                field.set(handle, replacement);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unable to install exact live profile", unwrap(failure));
            }
        }

        private Tracker tracker(Player actor) {
            try {
                Object handle = handle(actor);
                Optional<Object> directTracked =
                        BukkitTrackingReflection.directTrackedEntity(handle);
                Object tracked = directTracked.isPresent()
                        ? directTracked.orElseThrow()
                        : trackedEntityFromChunkMap(handle);
                if (tracked == null) {
                    return Tracker.empty();
                }
                Field seenByField = fieldByCollection(tracked.getClass(), "seenBy");
                Collection<?> seenBy = (Collection<?>) seenByField.get(tracked);
                List<Player> observers = new ArrayList<>();
                for (Object connection : seenBy) {
                    Object observerHandle;
                    try {
                        observerHandle = invokeAny(connection,
                                legacyMapped ? List.of("f") : List.of("getPlayer"));
                    } catch (NoSuchMethodException missingAccessor) {
                        Field playerField = fieldAssignable(
                                connection.getClass(), handle.getClass(), "player");
                        observerHandle = playerField.get(connection);
                    }
                    Player observer = bukkitPlayer(observerHandle);
                    if (observer != actor) {
                        observers.add(observer);
                    }
                }
                Method remove = BukkitTrackingReflection.playerMethod(
                        tracked.getClass(), handle.getClass(),
                        legacyMapped ? "a" : "removePlayer");
                Method update = BukkitTrackingReflection.playerMethod(
                        tracked.getClass(), handle.getClass(),
                        legacyMapped ? "b" : "updatePlayer");
                return new Tracker(
                        tracked, remove, update, seenBy, List.copyOf(observers));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unable to acquire exact observer tracking", unwrap(failure));
            }
        }

        private Object trackedEntityFromChunkMap(Object handle)
                throws ReflectiveOperationException {
            Object level = invokeAny(handle,
                    legacyMapped ? List.of("x") : List.of("serverLevel", "level"));
            Object chunkSource = invokeAny(level,
                    legacyMapped ? List.of("k") : List.of("getChunkSource"));
            Field chunkMapField = fieldByTypeNames(chunkSource.getClass(),
                    legacyMapped ? List.of("PlayerChunkMap") : List.of("ChunkMap"));
            Object chunkMap = chunkMapField.get(chunkSource);
            Field entityMapField = fieldByNameOrMap(chunkMap.getClass(), "entityMap");
            Object entityMap = entityMapField.get(chunkMap);
            int entityId = ((Number) invokeAny(handle,
                    legacyMapped ? List.of("af") : List.of("getId"))).intValue();
            return invokeCompatible(entityMap, "get", entityId);
        }

        private void refreshPlayerInfo(Player recipient, Player actor) {
            try {
                Object recipientHandle = handle(recipient);
                Object actorHandle = handle(actor);
                Class<?> removeType = Class.forName(
                        "net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket",
                        false, classLoader);
                Constructor<?> removeConstructor = removeType.getConstructor(List.class);
                Object remove = removeConstructor.newInstance(List.of(actor.getUniqueId()));
                Class<?> updateType = Class.forName(
                        "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket",
                        false, classLoader);
                Method initialize = playerInfoInitializer(updateType);
                Object update = initialize.invoke(null, List.of(actorHandle));
                Object recipientConnection = connection(recipientHandle);
                send(recipientConnection, remove);
                send(recipientConnection, update);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unable to send exact player info packets", unwrap(failure));
            }
        }

        private Object replacementProperties(
                Object current, Optional<SignedTexturesProperty> textures)
                throws ReflectiveOperationException {
            try {
                invokeCompatible(current, "removeAll", TEXTURES);
                if (textures.isPresent()) {
                    invokeCompatible(current, "put", TEXTURES, property(textures.orElseThrow()));
                }
                return current;
            } catch (InvocationTargetException failure) {
                if (!(failure.getCause() instanceof UnsupportedOperationException)) {
                    throw failure;
                }
            }
            Class<?> immutable = Class.forName(
                    "com.google.common.collect.ImmutableListMultimap", false, classLoader);
            Object builder = immutable.getMethod("builder").invoke(null);
            Method put = method(builder.getClass(), List.of("put"), 2).orElseThrow();
            Collection<?> entries = (Collection<?>) invoke(current, "entries");
            for (Object raw : entries) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) raw;
                if (!TEXTURES.equals(entry.getKey())) {
                    put.invoke(builder, entry.getKey(), entry.getValue());
                }
            }
            if (textures.isPresent()) {
                put.invoke(builder, TEXTURES, property(textures.orElseThrow()));
            }
            Object built = invoke(builder, "build");
            for (Constructor<?> constructor : current.getClass().getDeclaredConstructors()) {
                if (constructor.getParameterCount() == 1
                        && constructor.getParameterTypes()[0].isInstance(built)) {
                    constructor.setAccessible(true);
                    return constructor.newInstance(built);
                }
            }
            throw new NoSuchMethodException("PropertyMap immutable constructor");
        }

        private Object replacementProfile(Object current, Object replacementProperties)
                throws ReflectiveOperationException {
            Optional<Method> withProperties = method(
                    current.getClass(), List.of("withProperties"), 1);
            if (withProperties.isPresent()) {
                return withProperties.orElseThrow().invoke(current, replacementProperties);
            }
            UUID id = (UUID) invokeAny(current, List.of("getId", "id"));
            String name = (String) invokeAny(current, List.of("getName", "name"));
            for (Constructor<?> constructor : current.getClass().getDeclaredConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 3 && parameters[0] == UUID.class
                        && parameters[1] == String.class
                        && parameters[2].isInstance(replacementProperties)) {
                    constructor.setAccessible(true);
                    return constructor.newInstance(id, name, replacementProperties);
                }
            }
            return current;
        }

        private Object properties(Object profile) throws ReflectiveOperationException {
            return invokeAny(profile, List.of("getProperties", "properties"));
        }

        private Object property(SignedTexturesProperty textures)
                throws ReflectiveOperationException {
            Class<?> type = Class.forName(
                    "com.mojang.authlib.properties.Property", false, classLoader);
            return type.getConstructor(String.class, String.class, String.class)
                    .newInstance(TEXTURES, textures.value(), textures.signature());
        }

        private Object sessionService(Object minecraftServer)
                throws ReflectiveOperationException {
            Optional<Method> direct = allMethods(minecraftServer.getClass()).stream()
                    .filter(candidate -> candidate.getParameterCount() == 0)
                    .filter(candidate -> candidate.getReturnType().getName().equals(
                            "com.mojang.authlib.minecraft.MinecraftSessionService"))
                    .peek(candidate -> candidate.setAccessible(true))
                    .findFirst();
            if (direct.isPresent()) {
                return direct.orElseThrow().invoke(minecraftServer);
            }
            Object services = invoke(minecraftServer, "services");
            return invoke(services, "sessionService");
        }

        private Method playerInfoInitializer(Class<?> updateType)
                throws NoSuchMethodException {
            List<String> names = legacyMapped
                    ? List.of("a") : List.of("createPlayerInitializing");
            return allMethods(updateType).stream()
                    .filter(candidate -> names.contains(candidate.getName()))
                    .filter(candidate -> Modifier.isStatic(candidate.getModifiers()))
                    .filter(candidate -> candidate.getReturnType() == updateType)
                    .filter(candidate -> candidate.getParameterCount() == 1)
                    .filter(candidate -> Collection.class.isAssignableFrom(
                            candidate.getParameterTypes()[0]))
                    .peek(candidate -> candidate.setAccessible(true))
                    .findFirst().orElseThrow(() -> new NoSuchMethodException(
                            updateType.getName() + " player-info initializer"));
        }

        private static Object handle(Player player) throws ReflectiveOperationException {
            return invoke(player, "getHandle");
        }

        private static Player bukkitPlayer(Object handle) throws ReflectiveOperationException {
            return (Player) invoke(handle, "getBukkitEntity");
        }

        private static Object connection(Object playerHandle) throws ReflectiveOperationException {
            try {
                return fieldByName(playerHandle.getClass(), "connection").get(playerHandle);
            } catch (NoSuchFieldException missing) {
                try {
                    return fieldByName(playerHandle.getClass(), "c").get(playerHandle);
                } catch (NoSuchFieldException legacyMissing) {
                    return invokeAny(playerHandle, List.of("connection"));
                }
            }
        }

        private static void send(Object connection, Object packet)
                throws ReflectiveOperationException {
            Method send = java.util.Arrays.stream(connection.getClass().getMethods())
                    .filter(candidate -> candidate.getName().equals("send")
                            || candidate.getName().equals("a"))
                    .filter(candidate -> candidate.getParameterCount() == 1)
                    .filter(candidate -> candidate.getParameterTypes()[0].isInstance(packet))
                    .findFirst().orElseThrow();
            send.invoke(connection, packet);
        }

        private static Object invoke(Object target, String name, Object... arguments)
                throws ReflectiveOperationException {
            return invokeCompatible(target, name, arguments);
        }

        private static Object invokeAny(Object target, List<String> names, Object... arguments)
                throws ReflectiveOperationException {
            for (String name : names) {
                try {
                    return invokeCompatible(target, name, arguments);
                } catch (NoSuchMethodException ignored) {
                }
            }
            throw new NoSuchMethodException(target.getClass().getName() + " " + names);
        }

        private static Object invokeCompatible(Object target, String name, Object... arguments)
                throws ReflectiveOperationException {
            Method candidate = allMethods(target.getClass()).stream()
                    .filter(method -> method.getName().equals(name))
                    .filter(method -> method.getParameterCount() == arguments.length)
                    .filter(method -> compatible(method.getParameterTypes(), arguments))
                    .findFirst().orElseThrow(() -> new NoSuchMethodException(
                            target.getClass().getName() + '#' + name));
            candidate.setAccessible(true);
            return candidate.invoke(target, arguments);
        }

        private static Optional<Method> method(
                Class<?> owner, List<String> names, int parameterCount) {
            return allMethods(owner).stream()
                    .filter(candidate -> names.contains(candidate.getName()))
                    .filter(candidate -> candidate.getParameterCount() == parameterCount)
                    .peek(candidate -> candidate.setAccessible(true))
                    .findFirst();
        }

        private static List<Method> allMethods(Class<?> owner) {
            List<Method> result = new ArrayList<>();
            for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
                result.addAll(List.of(current.getDeclaredMethods()));
            }
            result.addAll(List.of(owner.getMethods()));
            return result;
        }

        private static boolean compatible(Class<?>[] parameters, Object[] arguments) {
            for (int index = 0; index < parameters.length; index++) {
                if (arguments[index] == null) {
                    continue;
                }
                Class<?> parameter = wrap(parameters[index]);
                if (!parameter.isInstance(arguments[index])) {
                    return false;
                }
            }
            return true;
        }

        private static Class<?> wrap(Class<?> type) {
            if (!type.isPrimitive()) {
                return type;
            }
            if (type == int.class) return Integer.class;
            if (type == long.class) return Long.class;
            if (type == boolean.class) return Boolean.class;
            if (type == byte.class) return Byte.class;
            if (type == short.class) return Short.class;
            if (type == char.class) return Character.class;
            if (type == float.class) return Float.class;
            if (type == double.class) return Double.class;
            return Void.class;
        }

        private static Field fieldByName(Class<?> owner, String name) throws NoSuchFieldException {
            for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                }
            }
            throw new NoSuchFieldException(owner.getName() + '#' + name);
        }

        private static Field fieldAssignable(Class<?> owner, Class<?> type, String preferred)
                throws NoSuchFieldException {
            try {
                return fieldByName(owner, preferred);
            } catch (NoSuchFieldException ignored) {
                for (Field field : allFields(owner)) {
                    if (field.getType().isAssignableFrom(type)) {
                        field.setAccessible(true);
                        return field;
                    }
                }
                throw new NoSuchFieldException(owner.getName() + " assignable " + type.getName());
            }
        }

        private static Field fieldByTypeNames(Class<?> owner, List<String> markers)
                throws NoSuchFieldException {
            for (Field field : allFields(owner)) {
                if (markers.stream().anyMatch(marker ->
                        field.getType().getName().contains(marker))) {
                    field.setAccessible(true);
                    return field;
                }
            }
            throw new NoSuchFieldException(owner.getName() + " types " + markers);
        }

        private static Field fieldByNameOrMap(Class<?> owner, String preferred)
                throws NoSuchFieldException {
            try {
                return fieldByName(owner, preferred);
            } catch (NoSuchFieldException ignored) {
                for (Field field : allFields(owner)) {
                    if (field.getType().getName().contains("Int2ObjectMap")) {
                        field.setAccessible(true);
                        return field;
                    }
                }
                throw new NoSuchFieldException(owner.getName() + " entity map");
            }
        }

        private static Field fieldByCollection(Class<?> owner, String preferred)
                throws NoSuchFieldException {
            try {
                return fieldByName(owner, preferred);
            } catch (NoSuchFieldException ignored) {
                for (Field field : allFields(owner)) {
                    if (Collection.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        return field;
                    }
                }
                throw new NoSuchFieldException(owner.getName() + " observer collection");
            }
        }

        private static List<Field> allFields(Class<?> owner) {
            List<Field> result = new ArrayList<>();
            for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
                result.addAll(List.of(current.getDeclaredFields()));
            }
            return result;
        }

        private static Throwable unwrap(ReflectiveOperationException failure) {
            return failure instanceof InvocationTargetException invocation
                    && invocation.getCause() != null ? invocation.getCause() : failure;
        }

        private static final class Tracker {
            private final Object tracked;
            private final Method remove;
            private final Method update;
            private final Collection<?> seenBy;
            private final List<Player> observers;

            private Tracker(
                    Object tracked,
                    Method remove,
                    Method update,
                    Collection<?> seenBy,
                    List<Player> observers) {
                this.tracked = tracked;
                this.remove = remove;
                this.update = update;
                this.seenBy = seenBy;
                this.observers = observers;
            }

            private static Tracker empty() {
                return new Tracker(null, null, null, List.of(), List.of());
            }

            private List<Player> observers() {
                return observers;
            }

            private void untrack(Player player) {
                invokeTracking(remove, player);
            }

            private void retrack(Player player) {
                invokeTracking(update, player);
            }

            private boolean isTracking(Player player) {
                if (tracked == null) {
                    return false;
                }
                try {
                    return seenBy.contains(connection(handle(player)));
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(
                            "Unable to verify restored observer tracking", unwrap(failure));
                }
            }

            private void invokeTracking(Method method, Player player) {
                if (tracked == null || method == null) {
                    return;
                }
                try {
                    method.invoke(tracked, handle(player));
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Exact tracking operation failed", unwrap(failure));
                }
            }
        }
    }
}
