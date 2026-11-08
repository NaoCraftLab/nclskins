package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.api.*;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.provider.*;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

final class GalleryDoneRecoveryTest {
    @TempDir Path directory;
    final UUID account = GalleryApplyTest.ACCOUNT;
    final UUID preset = GalleryApplyTest.A;
    final Deque<Runnable> workers = new ArrayDeque<>();
    final Deque<Runnable> clients = new ArrayDeque<>();
    final AtomicReference<GameSessionTokenSource.SessionIdentity> identity = new AtomicReference<>(
            new GameSessionTokenSource.SessionIdentity(account, "Fixture"));
    final AtomicInteger tokens = new AtomicInteger();
    final AtomicInteger gets = new AtomicInteger();
    final AtomicInteger mutations = new AtomicInteger();
    final AtomicInteger skinWrites = new AtomicInteger();
    final AtomicInteger capeWrites = new AtomicInteger();
    boolean workerLane;
    final AtomicInteger clientDurableReads = new AtomicInteger();
    final AtomicInteger workerDurableReads = new AtomicInteger();
    boolean online;
    boolean inline;
    boolean limited;
    final AtomicInteger optionWrites = new AtomicInteger();
    final AtomicReference<com.naocraftlab.skins.client.OuterLayerVisibility> options = new AtomicReference<>(
            com.naocraftlab.skins.client.OuterLayerVisibility.allVisible());
    Runnable onGet = () -> {};
    boolean rejectProfile;
    boolean expectProviders;
    Runnable afterMutation = () -> {};
    RemoteProfile profile = new RemoteProfile(account, "Fixture", List.of(), List.of(), Set.of());
    NclSkinsStorage storage;
    DefaultClientOperations operations;
    ClientRuntime runtime;

    void setup() throws Exception {
        storage = new NclSkinsStorage(directory, new PngValidator(), GalleryApplyTest.CLOCK);
        storage.initialize();
        storage.updateAccount(account, old -> new AccountState(old.schemaVersion(), account, List.of(),
                List.of(new AppearancePreset(preset, "Fixture", SkinReference.accountDefault(), null,
                        GalleryApplyTest.NOW, GalleryApplyTest.NOW)), GalleryApplyTest.NOW));
        GameSessionTokenSource source = new GameSessionTokenSource() {
            public SessionIdentity currentSession() { return identity.get(); }
            public <T,E extends Exception> T withAccessToken(TokenRequest<T,E> request) throws E {
                tokens.incrementAndGet();
                if (!online) throw new IllegalStateException("Synthetic unavailable session");
                return request.execute("synthetic-test-token");
            }
        };
        ProfileApi api = new ProfileApi() {
            public RemoteProfile getProfile(String ignored) throws ProfileApiException {
                gets.incrementAndGet();
                if (!expectProviders) assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle(), "Close before profile HTTP");
                assertTrue(workerLane || inline);
                onGet.run();
                if (rejectProfile) {
                    limited = true;
                    throw new ProfileApiException(ApiFailureKind.RATE_LIMITED,"Synthetic rate limit",429,Duration.ofSeconds(5),false);
                }
                return profile;
            }
            public void uploadSkin(String ignored, SkinVariant variant, byte[] bytes) { mutations.incrementAndGet(); }
            public void resetSkin(String ignored) { mutations.incrementAndGet(); skinWrites.incrementAndGet(); }
            public void activateCape(String ignored, String cape) { mutations.incrementAndGet(); }
            public void deactivateCape(String ignored) {
                mutations.incrementAndGet(); capeWrites.incrementAndGet();
                profile = new RemoteProfile(account,"Fixture",List.of(),List.of(),Set.of());
                afterMutation.run();
            }
            public Optional<Duration> rateLimitRemaining() { return limited ? Optional.of(Duration.ofSeconds(5)) : Optional.empty(); }
        };
        byte[] png = Files.readAllBytes(Path.of("../core/src/test/resources/cpm/classic-a.png"));
        var graph = TestClientGraph.create(source, api, storage, (BundledSkinSource) model -> png, GalleryApplyTest.CLOCK);
        operations = graph.operations();
        ClientExecutor client = new ClientExecutor() {
            public boolean isClientThread() { return !workerLane; }
            public void execute(Runnable action) {
                if (!workerLane || inline) {
                    boolean previous = workerLane;
                    workerLane = false;
                    try { action.run(); } finally { workerLane = previous; }
                } else clients.add(action);
            }
        };
        graph.attachProviders(resolved -> PlayerAppearanceSink.ApplyResult.UPDATED, client);
        ClientOperations observedOperations = (ClientOperations) java.lang.reflect.Proxy.newProxyInstance(
                ClientOperations.class.getClassLoader(), new Class<?>[]{ClientOperations.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("durableAppearance")) {
                        (workerLane ? workerDurableReads : clientDurableReads).incrementAndGet();
                    }
                    try { return method.invoke(operations, arguments); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        runtime = new ClientRuntime(new DefaultRuntimeServices(), observedOperations, client,
                () -> CompletableFuture.completedFuture(Optional.empty()),
                action -> {
                    if (inline) {
                        boolean previous = workerLane;
                        workerLane = true;
                        try { action.run(); } finally { workerLane = previous; }
                    } else workers.add(action);
                }, UiMessage::key,
                Optional.empty(), Optional.of(new OuterLayerVisibilityController() {
                    public com.naocraftlab.skins.client.OuterLayerVisibility current() { return options.get(); }
                    public void applyDurable(com.naocraftlab.skins.client.OuterLayerVisibility value) {
                        assertFalse(workerLane && !inline); optionWrites.incrementAndGet(); options.set(value);
                    }
                }), Optional.empty(), DiagnosticSinks.discarding());
        runtime.subscribe(snapshot -> assertFalse(workerLane && !inline, "Client-only publication"));
        runtime.initialize();
        drain();
        operations.usePreset(preset);
        storage.updateAppearance(account, current -> {
            var skin = current.providers().skin();
            var cape = current.providers().cape();
            return new AccountAppearanceState(current.schemaVersion(), account, current.intentRevision(), current.activePresetId(),
                    current.skinSha256(), current.skinVariant(), current.capeId(), current.outerLayerVisibility(),
                    AppearanceSyncStatus.UNKNOWN, current.settledRevision(), current.updatedAt(),
                    new AppearanceProviders(skin.settle(skin.minecraftDelivery(), ProviderDelivery.Status.UNKNOWN, null),
                            cape.settle(cape.minecraftDelivery(), ProviderDelivery.Status.UNKNOWN, null)));
        });
        runtime.view(854,480,0,0);
        runtime.dispatchWidget("gallery.reset");
        drain();
        assertEquals(AppearanceSyncStatus.UNKNOWN, runtime.snapshot().syncStatus());
        assertTrue(runtime.view(854,480,0,0).widget("gallery.done").isPresent());
        online = true;
        tokens.set(0); gets.set(0); mutations.set(0);
        optionWrites.set(0);
        options.set(com.naocraftlab.skins.client.OuterLayerVisibility.noneVisible());
    }
    void work() { workerLane = true; try { workers.remove().run(); } finally { workerLane = false; } }
    void client() { while (!clients.isEmpty()) clients.remove().run(); }
    void drain() { int budget=100; while (!workers.isEmpty() || !clients.isEmpty()) { assertTrue(budget-- > 0); if (!workers.isEmpty()) work(); client(); } }
    void done() { runtime.dispatchWidget("gallery.done"); assertEquals(ClientSnapshot.Lifecycle.CLOSED,runtime.snapshot().lifecycle()); }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void doneCurrentnessAndPublicationNeverReadDurableStateOnClientLane(boolean inlineWorker) throws Exception {
        setup(); inline = inlineWorker;
        clientDurableReads.set(0); workerDurableReads.set(0);
        done(); drain();
        assertEquals(1, gets.get());
        assertEquals(AppearanceSyncStatus.OFFICIAL, runtime.snapshot().syncStatus());
        assertTrue(workerDurableReads.get() > 0, "Real worker durable authority must execute");
        assertEquals(0, clientDurableReads.get(), "Done completion/publication must be a cheap client fence");
        runtime.close();
    }

    @Test void doneCooldownAndRepeatedAdmissionNeverReadDurableStateOnClientLane() throws Exception {
        setup(); limited = true;
        clientDurableReads.set(0); workerDurableReads.set(0);
        done(); drain(); runtime.reopen(); drain();
        runtime.view(854,480,0,0); done(); drain();
        limited = false; runtime.tick(); drain();
        assertEquals(1, gets.get());
        assertEquals(AppearanceSyncStatus.OFFICIAL, storage.loadAppearance(account).syncStatus());
        assertTrue(workerDurableReads.get() > 0);
        assertEquals(0, clientDurableReads.get(), "Coalescing/cooldown/publication cannot read storage");
        runtime.close();
    }

    @Test void matchingProfileSettlesExistingIntentAfterRealDoneWithoutNewAssignment() throws Exception {
        setup();
        var before = storage.loadAppearance(account);
        var accountBefore = storage.loadOrCreateAccount(account);
        done();
        assertEquals(0, gets.get());
        drain();
        assertEquals(1, gets.get(), "Real Done must authorize fresh unresolved recovery");
        assertEquals(0, mutations.get());
        var after = storage.loadAppearance(account);
        assertEquals(AppearanceSyncStatus.OFFICIAL, after.syncStatus());
        assertEquals(before.intentRevision(), after.intentRevision());
        assertEquals(before.activePresetId(), after.activePresetId());
        assertEquals(accountBefore, storage.loadOrCreateAccount(account));
        runtime.close();
    }

    @Test void doneRecoveryDoesNotReapplyOptionsMask() throws Exception {
        setup(); done(); drain();
        assertEquals(AppearanceSyncStatus.OFFICIAL, storage.loadAppearance(account).syncStatus());
        assertEquals(0, optionWrites.get(), "Recovery is not another local preset application");
        assertEquals(com.naocraftlab.skins.client.OuterLayerVisibility.noneVisible(), options.get());
        runtime.close();
    }

    @Test void standaloneDoneWaitsAndCoalescesOneScopedContinuation() throws Exception {
        setup(); limited = true; done(); drain();
        assertEquals(0,tokens.get()); assertEquals(0,gets.get());
        limited = false; runtime.tick(); runtime.tick(); drain();
        assertEquals(1,gets.get()); assertEquals(0,mutations.get());
        assertEquals(AppearanceSyncStatus.OFFICIAL,storage.loadAppearance(account).syncStatus());
        runtime.tick(); drain(); assertEquals(1,gets.get());
        runtime.close();
    }

    @Test void identityChangeBetweenAdmissionAndCoordinatorWithoutTickRevokesHttp() throws Exception {
        setup(); done(); work();
        identity.set(new GameSessionTokenSource.SessionIdentity(account,"Replacement session"));
        drain();
        assertEquals(0,tokens.get()); assertEquals(0,gets.get());
        assertEquals(AppearanceSyncStatus.UNKNOWN,storage.loadAppearance(account).syncStatus());
        runtime.close();
    }

    @Test void responseIdentityChangeWithoutTickCannotSettleOldIntent() throws Exception {
        setup();
        onGet = () -> identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0,99),"Other"));
        done(); drain();
        assertEquals(1,gets.get()); assertEquals(0,mutations.get());
        assertEquals(AppearanceSyncStatus.UNKNOWN,storage.loadAppearance(account).syncStatus());
        runtime.close();
    }

    void reloadDraft() {
        runtime.dispatchWidget("gallery.reset"); drain(); runtime.view(854,480,0,0);
        tokens.set(0); gets.set(0); mutations.set(0); optionWrites.set(0);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void actualLastPresetDeletionRecoversDefaultResetWithoutReapplyingOptions(boolean mismatch) throws Exception {
        setup(); operations.deletePreset(new LibraryEditorPort.GalleryScope(operations.sessionIdentity().profileId(), 0, () -> true), preset); reloadDraft();
        if (mismatch) profile=new RemoteProfile(account,"Fixture",List.of(),List.of(new RemoteCape("fixture-cape",
                RemoteAssetState.ACTIVE,java.net.URI.create("https://textures.minecraft.net/texture/fixture"),"Fixture")),Set.of());
        assertTrue(storage.loadOrCreateAccount(account).presets().isEmpty());
        assertNull(storage.loadAppearance(account).activePresetId());
        var before = storage.loadAppearance(account);
        done(); drain();
        assertEquals(1,gets.get()); assertEquals(mismatch?1:0,mutations.get());
        assertEquals(AppearanceSyncStatus.OFFICIAL,storage.loadAppearance(account).syncStatus());
        assertEquals(before.intentRevision(),storage.loadAppearance(account).intentRevision());
        assertTrue(storage.loadOrCreateAccount(account).presets().isEmpty());
        assertEquals(0,optionWrites.get());
        assertEquals(com.naocraftlab.skins.client.OuterLayerVisibility.noneVisible(),options.get());
        runtime.close();
    }

    @Test void unknownMismatchDeliversOnlyMissingCape() throws Exception {
        setup();
        profile = new RemoteProfile(account,"Fixture",List.of(),List.of(new RemoteCape("fixture-cape",
                RemoteAssetState.ACTIVE,java.net.URI.create("https://textures.minecraft.net/texture/fixture"),"Fixture")),Set.of());
        long revision = storage.loadAppearance(account).intentRevision();
        done(); drain();
        assertEquals(1,gets.get()); assertEquals(0,skinWrites.get()); assertEquals(1,capeWrites.get());
        assertEquals(AppearanceSyncStatus.OFFICIAL,storage.loadAppearance(account).syncStatus());
        assertEquals(revision,storage.loadAppearance(account).intentRevision());
        assertEquals(0,optionWrites.get()); runtime.close();
    }

    @Test void partialMismatchKeepsConfirmedSkinAndRecoversCapeOnly() throws Exception {
        setup();
        storage.updateAppearance(account,c -> new AccountAppearanceState(c.schemaVersion(),account,c.intentRevision(),
                c.activePresetId(),c.skinSha256(),c.skinVariant(),c.capeId(),c.outerLayerVisibility(),
                AppearanceSyncStatus.PARTIAL,c.settledRevision(),c.updatedAt(),new AppearanceProviders(
                c.providers().skin().settle(c.providers().skin().minecraftDelivery(),ProviderDelivery.Status.CONFIRMED,
                        c.providers().skin().desired()),c.providers().cape())));
        reloadDraft();
        profile = new RemoteProfile(account,"Fixture",List.of(),List.of(new RemoteCape("fixture-cape",
                RemoteAssetState.ACTIVE,java.net.URI.create("https://textures.minecraft.net/texture/fixture"),"Fixture")),Set.of());
        var skin = storage.loadAppearance(account).providers().skin().minecraftDelivery();
        done(); drain();
        assertEquals(1,gets.get()); assertEquals(0,skinWrites.get()); assertEquals(1,capeWrites.get());
        assertEquals(skin,storage.loadAppearance(account).providers().skin().minecraftDelivery());
        assertEquals(AppearanceSyncStatus.OFFICIAL,storage.loadAppearance(account).syncStatus()); runtime.close();
    }

    @Test void secondRateLimitDoesNotAuthorizeAnEndlessContinuation() throws Exception {
        setup(); rejectProfile = true; done(); drain();
        assertEquals(1,gets.get()); runtime.tick(); drain(); assertEquals(1,gets.get());
        limited = false; runtime.tick(); drain(); assertEquals(2,gets.get());
        runtime.tick(); drain(); limited = false; runtime.tick(); drain();
        assertEquals(2,gets.get(),"Only one continuation belongs to this Done");
        runtime.close();
    }

    @Test void supersedingIntentDoesNotInheritCooldownAuthorization() throws Exception {
        setup(); limited = true; done(); drain(); operations.usePreset(preset);
        var before = storage.loadAppearance(account);
        limited = false; runtime.tick(); drain();
        assertEquals(0,tokens.get()); assertEquals(0,gets.get());
        assertEquals(before,storage.loadAppearance(account)); runtime.close();
    }

    @Test void disabledMinecraftDoneHasZeroCredentialsOrHttp() throws Exception {
        setup(); operations.disableProvider(AppearanceProviders.Component.SKIN,BuiltinProvider.MINECRAFT);
        operations.disableProvider(AppearanceProviders.Component.CAPE,BuiltinProvider.MINECRAFT); reloadDraft();
        var before = storage.loadAppearance(account); done(); drain();
        assertEquals(0,tokens.get()); assertEquals(0,gets.get()); assertEquals(0,mutations.get());
        assertEquals(before,storage.loadAppearance(account)); runtime.close();
    }

    @Test void shutdownBeforeWorkerHasZeroCredentialsOrHttp() throws Exception {
        setup(); done(); runtime.close(); drain();
        assertEquals(0,tokens.get()); assertEquals(0,gets.get());
    }

    @Test void queuedAccountRoundTripRetiresAuthorizationPermanently() throws Exception {
        setup(); done(); work();
        identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0,99),"Other"));
        work();
        identity.set(new GameSessionTokenSource.SessionIdentity(account,"Fixture")); client(); drain();
        assertEquals(0,tokens.get()); assertEquals(0,gets.get()); runtime.close();
    }

    @Test void providersParentIsRestoredBeforeInlineProfileRequest() throws Exception {
        setup();
        runtime.dispatchWidget("gallery.providers"); drain();
        runtime.dispatchWidget("providers.edit.OFFLINE"); drain();
        assertEquals("gallery",runtime.view(854,480,0,0).screenId());
        expectProviders = true; inline = true;
        onGet = () -> assertEquals("providers",runtime.view(854,480,0,0).screenId());
        runtime.dispatchWidget("gallery.done");
        assertEquals("providers",runtime.view(854,480,0,0).screenId());
        assertEquals(1,gets.get()); assertEquals(0,optionWrites.get()); runtime.close();
    }

    @Test void settledDoneHasZeroCredentialsOrProfileCalls() throws Exception {
        setup();
        storage.updateAppearance(account,c -> new AccountAppearanceState(c.schemaVersion(),account,c.intentRevision(),
                c.activePresetId(),c.skinSha256(),c.skinVariant(),c.capeId(),c.outerLayerVisibility(),
                AppearanceSyncStatus.OFFICIAL,c.intentRevision(),c.updatedAt(),new AppearanceProviders(
                c.providers().skin().settle(c.providers().skin().minecraftDelivery(),ProviderDelivery.Status.CONFIRMED,c.providers().skin().desired()),
                c.providers().cape().settle(c.providers().cape().minecraftDelivery(),ProviderDelivery.Status.CONFIRMED,c.providers().cape().desired()))));
        reloadDraft(); var before=storage.loadAppearance(account); done(); drain();
        assertEquals(0,tokens.get()); assertEquals(0,gets.get()); assertEquals(before,storage.loadAppearance(account)); runtime.close();
    }

    @Test void unknownWithoutActualDurableIntentDoesNotAcquireCredentials() throws Exception {
        setup();
        storage.updateAppearance(account,c -> new AccountAppearanceState(c.schemaVersion(),account,0,null,null,null,null,
                null,AppearanceSyncStatus.UNKNOWN,0,c.updatedAt(),AppearanceProviders.initial()));
        reloadDraft(); var before=storage.loadAppearance(account); done(); drain();
        assertEquals(0,tokens.get()); assertEquals(0,gets.get()); assertEquals(before,storage.loadAppearance(account)); runtime.close();
    }

    @Test void unavailableTokenDoesNotPollOrLoop() throws Exception {
        setup(); online=false; done(); drain();
        assertEquals(1,tokens.get()); assertEquals(0,gets.get());
        runtime.tick(); runtime.tick(); drain();
        assertEquals(1,tokens.get()); assertEquals(AppearanceSyncStatus.UNKNOWN,storage.loadAppearance(account).syncStatus()); runtime.close();
    }

    @ParameterizedTest @ValueSource(strings={"back","escape","providers","add","editor"})
    void otherExitDoesNotAuthorizeRecovery(String exit) throws Exception {
        setup(); var before=storage.loadAppearance(account);
        switch(exit) {
            case "back" -> runtime.dispatchWidget("gallery.back");
            case "escape" -> runtime.escapePressed();
            case "providers" -> runtime.dispatchWidget("gallery.providers");
            case "add" -> runtime.dispatchWidget("gallery.add");
            case "editor" -> { runtime.dispatchWidget("gallery.preset."+preset+".more"); runtime.dispatchWidget("gallery.preset."+preset+".edit"); }
        }
        drain(); assertEquals(0,tokens.get()); assertEquals(0,gets.get());
        assertEquals(before,storage.loadAppearance(account)); runtime.close();
    }

    @Test void configurationChangeAfterAdmissionRevokesBeforeHttp() throws Exception {
        setup(); done(); work();
        operations.moveProvider(AppearanceProviders.Component.SKIN,BuiltinProvider.MINECRAFT,-1);
        var before=storage.loadAppearance(account); drain();
        assertEquals(0,tokens.get()); assertEquals(0,gets.get()); assertEquals(before,storage.loadAppearance(account)); runtime.close();
    }

    @Test void configChangeDuringFreshResponseRevokesSettlement() throws Exception {
        setup(); onGet=() -> { try {
            operations.disableProvider(AppearanceProviders.Component.CAPE,BuiltinProvider.MINECRAFT);
        } catch(Exception failure) { throw new AssertionError(failure); } };
        done(); drain(); assertEquals(1,gets.get()); assertEquals(0,mutations.get());
        assertNotEquals(AppearanceSyncStatus.OFFICIAL,storage.loadAppearance(account).syncStatus()); runtime.close();
    }

    @Test void identityChangeAfterMutationRevokesSettlementWithoutRepeatingOptions() throws Exception {
        setup(); profile=new RemoteProfile(account,"Fixture",List.of(),List.of(new RemoteCape("fixture-cape",
                RemoteAssetState.ACTIVE,java.net.URI.create("https://textures.minecraft.net/texture/fixture"),"Fixture")),Set.of());
        afterMutation=() -> identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0,99),"Other"));
        done(); drain(); assertEquals(1,capeWrites.get());
        assertEquals(AppearanceSyncStatus.ATTEMPTING,storage.loadAppearance(account).syncStatus());
        assertEquals(0,optionWrites.get()); runtime.close();
    }

    @Test void heldCompletionCannotCloseOrClearBusyReplacementScreen() throws Exception {
        setup(); done(); work(); client(); work();
        assertFalse(clients.isEmpty());
        runtime.reopen(); var before=runtime.snapshot();
        assertTrue(before.busy()); assertEquals(ClientSnapshot.Lifecycle.INITIALIZING,before.lifecycle());
        client(); assertTrue(runtime.snapshot().busy());
        assertEquals(before.generation(),runtime.snapshot().generation());
        assertEquals(ClientSnapshot.Lifecycle.INITIALIZING,runtime.snapshot().lifecycle());
        drain(); assertEquals(ClientSnapshot.Lifecycle.READY,runtime.snapshot().lifecycle()); runtime.close();
    }

    @Test void independentlySettledIntentBetweenAdmissionAndWorkerHasNoHttp() throws Exception {
        setup(); done(); work();
        storage.updateAppearance(account,c -> new AccountAppearanceState(c.schemaVersion(),account,c.intentRevision(),
                c.activePresetId(),c.skinSha256(),c.skinVariant(),c.capeId(),c.outerLayerVisibility(),
                AppearanceSyncStatus.OFFICIAL,c.intentRevision(),c.updatedAt(),new AppearanceProviders(
                c.providers().skin().settle(c.providers().skin().minecraftDelivery(),ProviderDelivery.Status.CONFIRMED,c.providers().skin().desired()),
                c.providers().cape().settle(c.providers().cape().minecraftDelivery(),ProviderDelivery.Status.CONFIRMED,c.providers().cape().desired()))));
        drain(); assertEquals(0,tokens.get()); assertEquals(0,gets.get()); runtime.close();
    }

    @Test void withdrawnOwnedCapeDoesNotCreateReplacementIntentDuringDone() throws Exception {
        setup();
        storage.updateAppearance(account,c -> new AccountAppearanceState(c.schemaVersion(),account,c.intentRevision()+1,
                c.activePresetId(),c.skinSha256(),c.skinVariant(),"withdrawn",c.outerLayerVisibility(),
                AppearanceSyncStatus.UNKNOWN,c.settledRevision(),c.updatedAt(),c.providers().select(c.intentRevision()+1,
                c.providers().skin().desired(),c.providers().cape().offlineDesired(),new ProviderCape("withdrawn",null))));
        reloadDraft(); var before=storage.loadAppearance(account); done(); drain();
        assertEquals(1,gets.get()); assertEquals(0,mutations.get());
        assertEquals(before.intentRevision(),storage.loadAppearance(account).intentRevision());
        assertEquals("withdrawn",storage.loadAppearance(account).capeId()); runtime.close();
    }

    @Test void repeatedDoneAcrossReopenCoalescesTheSameCooldownAuthorization() throws Exception {
        setup(); limited=true; done(); drain(); runtime.reopen(); drain();
        runtime.view(854,480,0,0); done(); drain();
        assertEquals(0,tokens.get()); limited=false; runtime.tick(); drain();
        assertEquals(1,gets.get()); assertEquals(AppearanceSyncStatus.OFFICIAL,storage.loadAppearance(account).syncStatus()); runtime.close();
    }

    @Test void tickBeforeAdmissionCannotRetargetDoneToASupersedingIntent() throws Exception {
        setup(); limited=true; done(); runtime.tick(); drain();
        operations.usePreset(preset); var before=storage.loadAppearance(account);
        runtime.reopen(); drain(); runtime.view(854,480,0,0);
        limited=false; runtime.tick(); drain();
        assertEquals(0,tokens.get()); assertEquals(0,gets.get()); assertEquals(before,storage.loadAppearance(account)); runtime.close();
    }

    @Test void renewedCooldownBeforeContinuationWorkerDoesNotGrantAnotherContinuation() throws Exception {
        setup(); limited=true; done(); drain(); limited=false; runtime.tick();
        limited=true; drain(); runtime.tick(); drain(); limited=false; runtime.tick(); drain();
        assertEquals(0,tokens.get()); assertEquals(0,gets.get()); runtime.close();
    }

    @Test void rootParentClosesBeforeInlineProfileRequest() throws Exception {
        setup(); inline=true; done(); assertEquals(1,gets.get());
        assertEquals(0,optionWrites.get()); runtime.close();
    }
}
