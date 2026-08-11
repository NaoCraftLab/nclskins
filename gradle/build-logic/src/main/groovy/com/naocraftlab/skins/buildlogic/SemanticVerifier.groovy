package com.naocraftlab.skins.buildlogic

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

final class SemanticVerifier {
    static final Set<String> REQUIRED_KEYS = [
            'gui', 'textures', 'preview', 'appearance', 'loaderScreen', 'session',
            'clientExecutor', 'filePicker', 'bundledSkin', 'currentAppearance',
            'serverSignal', 'serverCommand',
        'serverProfileVerification', 'serverProfileMutation', 'serverTracking',
        'serverPlayerInfoPublication', 'serverLoader'
    ] as Set
    static final Set<String> TOP_LEVEL_KEYS = ['schemaVersion', 'capabilityKeys', 'sharedSuites', 'implementations'] as Set
    static final Set<String> IMPLEMENTATION_KEYS = ['capabilityKey', 'sharedSuite', 'leafSource'] as Set
    static final Set<String> SUITE_KEYS = ['tests', 'supportSources', 'semantics'] as Set
    static final Map<String, String> EXPECTED_SUITE_BY_KEY = [
            gui              : 'view-host-contract', textures: 'texture-ownership-and-normalization',
            preview: 'scoped-preview-contract', appearance: 'appearance-orchestration',
            loaderScreen     : 'client-loader-lifecycle',
        session: 'session-boundary', filePicker: 'picker-coordination',
            clientExecutor   : 'client-executor-contract',
            bundledSkin      : 'resource-pack-access-contract',
            currentAppearance: 'current-appearance-contract',
        serverSignal: 'server-refresh-notification', serverCommand: 'server-command-registration',
        serverProfileVerification: 'official-server-profile',
        serverProfileMutation: 'vanilla-observer-republication',
        serverTracking: 'vanilla-observer-republication',
        serverPlayerInfoPublication: 'vanilla-observer-republication',
        serverLoader: 'server-loader-lifecycle'
    ]
    static final Map<String, List<String>> SUITE_MARKERS = [
            'view-host-contract'           : ['ViewSpecGoldenTest', 'selectAllOnPrimaryClick', 'submitActionId'],
            'texture-ownership-and-normalization': ['TextureRegistryTck', 'PlayerSkinTextureNormalizer', 'NativePlayerSkinLifecycle', 'NativeTextureUploadTracker'],
            'scoped-preview-contract'            : ['PreviewIntent', 'EDITOR_DRAFT', 'EditorPreviewSession', 'EditorPreviewClock', 'ExactLocalPlayerScope', 'CenteredPipPreviewTransform', 'ScreenOwnedRenderTarget', 'PreviewSkinSourceTest'],
            'appearance-orchestration'           : ['AppearanceRefreshCoordinator', 'AppearanceReconnectTracker', 'AppearanceOverrideController', 'deferredReplacementRemainsActiveAndCanAttachWhenPlayerBecomesReady', 'SUPERSEDED', 'DEFERRED'],
            'client-loader-lifecycle'      : ['ClientProcessHost', 'afterReconnect', 'close'],
        'session-boundary': ['SessionValidationService', 'withSession', 'SECRET'],
            'client-executor-contract'     : ['ClientCapabilityContractsTest', 'ClientExecutor'],
        'picker-coordination': ['FilePickerCoordinator', 'concurrent'],
            'resource-pack-access-contract': ['ResourcePackSkinCatalog', 'CatalogGenerationTracker', 'selectedPackMenuRanks'],
            'current-appearance-contract'  : ['CurrentPlayerAppearanceSource', 'currentPlayerAppearance'],
        'server-refresh-notification': ['ServerAppearanceRefreshNotifier', 'RemoteAppearanceImpact', 'CONFIRMED_CHANGED', 'confirmedReconciliationStillNotifiesAfterGalleryCloses', 'postMutationLocalFailureStillNotifiesServerWithoutPublishingOutcomeData', 'confirmedPartialReconciliationSignalsExactlyOnce', 'disconnectedConfirmedSignalIsDroppedAndNeverReplayedAfterReconnect', 'readerOrConcurrentLoserWithoutOwnedOutcomeNeverSignals'],
        'official-server-profile': ['OfficialSessionProfileClient', 'OfficialTextureAppearanceParser', 'timestampTransportAndSignatureChangesDoNotChangeTheSemanticKey', 'parsesRetryAfterDeltaAndHttpDateWithSafeFallback', 'mismatchedOfficialIdentityIsRejectedBeforePublication'],
        'vanilla-observer-republication': ['VanillaBatchAppearancePublisher', 'continuesAcrossTicksAndNeverExceedsDeliveryBudget', 'reportsTotalAndMaximumPlatformThreadTimeSeparatelyAcrossTicks', 'semanticCompletionResumesOnFollowingLogicalTickWithoutFreshSameTickBudget', 'retriesFailedRetrackBeforeCompletingAndRestoresExactPair', 'cancelledHeadRetainsRetrackBarrierUntilRecoveryBeforeNextInstall', 'sixtyFourActorBatchKeepsOneRecipientFanoutAcrossOneThousandPlayers', 'watcherChannelRetracksBeforeLargeTabOnlyTail', 'explicitSupersedeFencesAdmittedIntentAndDoesNotPoisonFutureIntent', 'concurrentIntentCannotEnterBetweenLatestCheckAndProfileInstall', 'visibilityPortPreventsProfileDisclosureToHiddenRecipient', 'oneThousandDistinctSignalsAreAdmittedAndDrainWithoutLocalDrops', 'oneFiveTenAndFiftyChangesPerSecondAllConvergeAfterTheBurst', 'reconciliationAttemptsAreBoundedToOnePerFollowingTick', 'successfulWatcherRetryRefreshesWorldPairAfterInitializeFailure'],
        'server-loader-lifecycle': ['eligibilityRequiresOnlineIdentityOrExplicitAttestedProxyOptIn', 'trustedProxyForwarding', 'defaultsMatchThePortableScaleContract', 'sameListenerRegistrationIsIdempotentAndIdentityBound', 'changedAssuranceRotatesGenerationAndSupersedesInFlightTrust', 'reconnectSupersedesOldGenerationAndLateDisconnectCannotRemoveNewBinding'],
        'server-command-registration': ['ServerRefreshCommandProtocol', 'commandNameIsExactVersionedAndCarriesNoAccountPayload', 'advertisementRequiresOnlyAPlayerAndLiveService', 'onlyAcceptedAndCoalescedAdmissionsSucceed']
    ]
    static final Pattern PLATFORM_IMPORT = Pattern.compile('(?m)^\\s*import\\s+(?:com\\.mojang\\.authlib(?:\\.|;)|net\\.minecraft(?:\\.|;)|net\\.fabricmc(?:\\.|;)|net\\.neoforged(?:\\.|;)|net\\.minecraftforge(?:\\.|;)|org\\.bukkit(?:\\.|;)|org\\.spongepowered\\.asm(?:\\.|;))')

    static List<String> verify(Path root, Map catalog, Map abi, Map coverage) {
        List<String> errors = []
        if (coverage.schemaVersion != 1) errors.add('capability semantic coverage schemaVersion must be 1')
        if ((coverage.keySet() as Set) != TOP_LEVEL_KEYS) errors.add("capability semantic coverage must contain exactly ${TOP_LEVEL_KEYS.sort()}")
        if (!(coverage.capabilityKeys instanceof List) || (coverage.capabilityKeys as Set) != REQUIRED_KEYS || coverage.capabilityKeys.size() != REQUIRED_KEYS.size()) errors.add("capabilityKeys must contain exactly ${REQUIRED_KEYS.sort()}")
        Map<String, String> selected = [:]
        Map<String, List<Map>> targetsByImplementation = [:].withDefault { [] }
        catalog.targets.each { Map target ->
            REQUIRED_KEYS.each { String key ->
                String implementation = target.capabilities[key]?.toString()
                if (implementation == null || implementation.isBlank()) {
                    errors.add("${target.id}: missing ${key} implementation")
                } else {
                    if (selected.containsKey(implementation) && selected[implementation] != key) errors.add("${implementation}: selected as both ${selected[implementation]} and ${key}")
                    selected.putIfAbsent(implementation, key)
                    targetsByImplementation[implementation].add(target)
                }
            }
        }
        Map implementations = coverage.implementations instanceof Map ? coverage.implementations as Map : [:]
        if ((implementations.keySet() as Set) != (selected.keySet() as Set)) errors.add("semantic manifest must exactly cover selected native implementation IDs; missing=${(selected.keySet() - implementations.keySet()).sort()}, unused=${(implementations.keySet() - selected.keySet()).sort()}")
        Map abiImplementations = abi.implementations instanceof Map ? abi.implementations as Map : [:]
        Map declarations = catalog.capabilityImplementations as Map
        Set<String> usedSuites = [] as Set
        Map<Path, Set<Path>> leafBundleRoots = [:]
        implementations.each { Object rawId, Object rawEntry ->
            String implementation = rawId.toString()
            if (!(rawEntry instanceof Map) || (rawEntry.keySet() as Set) != IMPLEMENTATION_KEYS) {
                errors.add("${implementation}: implementation must contain exactly ${IMPLEMENTATION_KEYS.sort()}")
                return
            }
            Map entry = rawEntry as Map
            String key = entry.capabilityKey?.toString()
            if (key != selected[implementation]) errors.add("${implementation}: manifest kind ${key} does not match catalog kind ${selected[implementation]}")
            Map declaration = declarations[implementation] instanceof Map ? declarations[implementation] as Map : [:]
            String abiId = declaration.abiImplementation?.toString()
            String abiKind = abiImplementations[abiId] instanceof Map ? abiImplementations[abiId].kind?.toString() : null
            if (abiKind != selected[implementation]) errors.add("${implementation}: ABI kind ${abiKind} does not match catalog kind ${selected[implementation]}")
            String suite = entry.sharedSuite?.toString()
            if (suite == null || suite.isBlank()) errors.add("${implementation}: sharedSuite must be non-empty")
            else {
                usedSuites.add(suite)
                if (suite != EXPECTED_SUITE_BY_KEY[key]) errors.add("${implementation}: ${key} leaves must use ${EXPECTED_SUITE_BY_KEY[key]}, got ${suite}")
            }
            Path source = repositoryFile(root, entry.leafSource, "${implementation}.leafSource", errors)
            if (source == null) return
            String bundle = declaration.bundle?.toString()
            Set<Path> roots = bundleRoots(root, catalog.sourceBundles as Map, bundle)
            Set<Path> previousRoots = leafBundleRoots.putIfAbsent(source, roots)
            if (previousRoots != null && previousRoots.intersect(roots).isEmpty()) errors.add("${implementation}: shared leaf source ${root.relativize(source)} must be selected through one intentional common bundle")
            if (roots.isEmpty() || !roots.any { source.startsWith(it) }) errors.add("${implementation}: leaf source ${root.relativize(source)} is outside its catalog-selected source bundle")
            verifyLeaf(implementation, key, Files.readString(source), errors)
            if (implementation == 'submission-1.21.11') {
                verifySubmission12111GuiBundle(roots, errors)
            }
            if (key == 'preview') verifyPreviewBundle(implementation, roots, errors)
        }
        verifySuites(root, coverage.sharedSuites, usedSuites, errors)
        verifyRuntimeBoundary(root, errors)
        verifyPublicationBoundary(root, errors)
        errors
    }

    static void verifySubmission12111GuiBundle(Set<Path> roots, List<String> errors) {
        StringBuilder sources = new StringBuilder()
        roots.findAll(Files::isDirectory).each { Path sourceRoot ->
            Files.walk(sourceRoot).withCloseable { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith('.java') }
                        .forEach { sources.append(Files.readString(it)).append('\n') }
            }
        }
        String text = sources.toString().replaceAll('\\s+', ' ')
        [
                'ACTION_ICON_RENDER_SIZE, ACTION_ICON_RENDER_SIZE, ACTION_ICON_TEXTURE_SIZE, ACTION_ICON_TEXTURE_SIZE, ACTION_ICON_TEXTURE_SIZE, ACTION_ICON_TEXTURE_SIZE);',
                'extends AbstractScrollArea',
                'super.mouseScrolled(mouseX, mouseY, 0.0, amount)',
                'protected double scrollRate() { return wheelStep;',
                'public int maxScrollAmount() { return maximum;',
                'PointerRouting.scrollSurface(current, x, y)',
                'runtime.nativeScrollPositionChanged(surface.id(), offset)',
                'synchronization.acceptedRuntimeOffset(offsetPixels)',
                'renderScrollbar(graphics, mouseX, mouseY)',
                'scrollController.render( graphics, OFFSCREEN_MOUSE_COORDINATE, OFFSCREEN_MOUSE_COORDINATE, partialTick)'
        ].each { String required ->
            if (!text.contains(required)) {
                errors.add("submission-1.21.11: native icon/scroll host lacks required marker '${required}'")
            }
        }
    }

    static void verifyPreviewBundle(
            String implementation, Set<Path> roots, List<String> errors) {
        StringBuilder sources = new StringBuilder()
        roots.findAll(Files::isDirectory).each { Path sourceRoot ->
            Files.walk(sourceRoot).withCloseable { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith('.java') }
                        .forEach { sources.append(Files.readString(it)).append('\n') }
            }
        }
        String text = sources.toString().replaceAll('\\s+', ' ')
        ['minecraft.player', 'EditorPreviewSession', 'ExactLocalPlayerScope',
         'extends RemotePlayer'].each { String required ->
            if (!text.contains(required)) {
                errors.add("${implementation}: live editor preview lacks required isolated-proxy marker (${required})")
            }
        }
        ['EditorPreviewClock', 'NativePlayerSkinLifecycle'].each { String required ->
            if (!text.contains(required)) {
                errors.add("${implementation}: editor preview lacks readiness/animation marker (${required})")
            }
        }
        if (implementation == 'avatar-pip-1.21.11') {
            ['submitEntityRenderState', 'submitSkinRenderState',
             'NclPreviewState', 'LivingEntityRendererPreviewMixin',
             'EntityRenderState state',
             'PlayerSkin.insecure', 'CenteredPlayerPreviewGeometry.centeredEntityTranslation(',
             'Minecraft12111SimplePreviewRenderer', 'ItemStack.EMPTY',
             'Minecraft12111BakedPreviewRenderState',
             'Minecraft12111BakedPreviewSubmission', 'GuiGraphicsPreviewMixin',
             'GuiRendererMixin', '@ModifyVariable', 'List.copyOf',
             'Minecraft12111PreviewContext', 'Minecraft12111PreviewScope',
             'GuiEntityRendererMixin', 'EditorPreviewLayerGuard',
             'Minecraft12111PreviewModelAnchors', 'ModelPartPreviewMixin',
             'renderPlayer.tickCount =', 'renderPlayer.avatarState().tick(',
             'ScreenOwnedRenderTarget', 'standaloneEquipment',
             'PlayerCapeModel', 'ElytraModel', 'ELYTRA_ROT_X', 'ELYTRA_ROT_Z',
             'ElytraModel.createLayer().bakeRoot()', 'Model<?> attachmentModel',
             'state.elytraRotX = CenteredPipPreviewTransform.ELYTRA_ROT_X',
             'state.elytraRotY = CenteredPipPreviewTransform.ELYTRA_ROT_Y',
             'state.elytraRotZ = CenteredPipPreviewTransform.ELYTRA_ROT_Z',
             'CenteredPipPreviewTransform.modelPitchRadians(state.pitchDegrees())',
             'CenteredPipPreviewTransform.applyPlayerPose('].each { String required ->
                if (!text.contains(required)) {
                    errors.add("${implementation}: 1.21.11 submission preview lacks required marker (${required})")
                }
            }
            if (text.contains('LivingEntityRenderState state')) {
                errors.add("${implementation}: layer redirect must match the erased EntityRenderState descriptor")
            }
        } else if (implementation.startsWith('avatar-pip-')) {
            ['Minecraft262PreviewContext', 'NclBakedPlayerRenderState',
             'NclBakedPlayerSubmission', 'GuiGraphicsExtractorPreviewMixin',
             'GuiRendererMixin', '@ModifyVariable', 'List.copyOf',
             'ScreenOwnedRenderTarget', 'NclBakedPlayerTarget',
             'standaloneEquipment', 'PlayerCapeModel', 'ElytraModel',
             'ELYTRA_ROT_X', 'ELYTRA_ROT_Z'].each { String required ->
                if (!text.contains(required)) {
                    errors.add("${implementation}: 26.x preview lacks deferred/composite marker (${required})")
                }
            }
            List<String> pitchMarkers = [
                    'Minecraft262BakedPlayerPose.applyPitch(pose, state.pitchDegrees())',
                    'CenteredPlayerPreviewGeometry.centeredEntityTranslation(',
                    'CenteredPipPreviewTransform.modelPitchRadians(pitchDegrees)',
                    'return CenteredPipPreviewTransform.pitchRadians(pitchDegrees)'
            ]
            pitchMarkers.each { String required ->
                if (!text.contains(required)) {
                    errors.add("${implementation}: 26.x preview lacks live/baked pitch split marker (${required})")
                }
            }
            if (text.contains('modelView.rotateX(')) {
                errors.add("${implementation}: 26.x preview pitch must stay in the centered submitted pose")
            }
        } else {
            ['tickCount', 'PreviewPlayer', 'PreviewScope.open'].each { String required ->
                if (!text.contains(required)) {
                    errors.add("${implementation}: legacy preview lacks isolated animation marker (${required})")
                }
            }
            ['bakeLayer(', 'getEntityModels()'].each { String forbidden ->
                if (text.contains(forbidden)) {
                    errors.add("${implementation}: static legacy preview must bypass intercepted model baking (${forbidden})")
                }
            }
        }
        ['minecraft.player.set', 'minecraft.player.getInventory()',
         'minecraft.options.setModelPart'].each { String forbidden ->
            if (text.contains(forbidden)) {
                errors.add("${implementation}: preview must not mutate the real local player (${forbidden})")
            }
        }
    }

    static void verifySuites(Path root, Object rawSuites, Set<String> used, List<String> errors) {
        if (!(rawSuites instanceof Map)) { errors.add('sharedSuites must be an object'); return }
        Map suites = rawSuites as Map
        if ((suites.keySet() as Set) != used) errors.add("sharedSuites must exactly cover referenced suite IDs; missing=${(used - suites.keySet()).sort()}, unused=${(suites.keySet() - used).sort()}")
        suites.each { Object rawId, Object rawSuite ->
            String id = rawId.toString()
            if (!(rawSuite instanceof Map) || (rawSuite.keySet() as Set) != SUITE_KEYS) { errors.add("${id}: suite must contain exactly ${SUITE_KEYS.sort()}"); return }
            Map suite = rawSuite as Map
            List tests = suite.tests instanceof List ? suite.tests as List : []
            List support = suite.supportSources instanceof List ? suite.supportSources as List : []
            List semantics = suite.semantics instanceof List ? suite.semantics as List : []
            if (tests.isEmpty()) errors.add("${id}: tests must be a non-empty array")
            if (!(suite.supportSources instanceof List)) errors.add("${id}: supportSources must be an array")
            if (semantics.isEmpty() || semantics.any { !(it instanceof String) || it.isBlank() } || semantics.size() != (semantics as Set).size()) errors.add("${id}: semantics must be unique non-empty strings")
            if ((tests + support).size() != ((tests + support) as Set).size()) errors.add("${id}: test/support paths must be unique")
            StringBuilder sources = new StringBuilder()
            tests.eachWithIndex { Object path, int index ->
                Path source = repositoryFile(root, path, "${id}.tests[${index}]", errors)
                if (source != null) {
                    String normalized = source.toString().replace(File.separatorChar, '/' as char)
                    String text = Files.readString(source)
                    if (!normalized.contains('/src/test/') || !source.fileName.toString().endsWith('Test.java')) errors.add("${id}: behavioral test is not a *Test.java source: ${path}")
                    else if (!text.contains('@Test') && !(text =~ /\bimplements\s+\w*Tck\b/).find()) errors.add("${id}: test source declares no behavioral tests: ${path}")
                    sources.append(text).append('\n')
                }
            }
            support.eachWithIndex { Object path, int index ->
                Path source = repositoryFile(root, path, "${id}.supportSources[${index}]", errors)
                if (source != null) sources.append(Files.readString(source)).append('\n')
            }
            SUITE_MARKERS.getOrDefault(id, []).each { String marker -> if (!sources.toString().contains(marker)) errors.add("${id}: behavioral sources lack required marker '${marker}'") }
        }
    }

    static void verifyLeaf(String implementation, String key, String text, List<String> errors) {
        String compact = text.replaceAll('\\s+', ' ')
        Map<String, List<String>> markers = [
                gui              : ['ClientRuntime'],
                textures         : ['extends AbstractTextureRegistry'],
                preview          : ['implements PreviewRenderer'],
                appearance       : ['implements PlayerAppearanceSink<AcknowledgedAppearanceAssets>'],
                session          : ['implements GameSessionTokenSource'],
                clientExecutor   : ['implements ClientExecutor'],
            filePicker: ['implements FilePicker', 'FilePickerCoordinator', 'new FilePickerCoordinator(', 'COORDINATOR.choose('],
                bundledSkin      : ['implements SkinCatalogSource'],
                currentAppearance: ['implements CurrentPlayerAppearanceSource'],
            serverSignal: ['implements ServerAppearanceRefreshNotifier', 'getConnection()', 'if (connection == null)', 'getChild(ServerRefreshCommandProtocol.ROOT_COMMAND)', 'root.getChild(ServerRefreshCommandProtocol.REFRESH_COMMAND)', 'ServerAppearanceRefreshCommandPath.isExactExecutableLeaf', 'sendCommand(ServerRefreshCommandProtocol.COMMAND)'],
            serverCommand: ['Commands.literal(ServerRefreshCommandProtocol.ROOT_COMMAND)', '.requires(MinecraftServerRefreshCommand::canRefresh)', 'Commands.literal(ServerRefreshCommandProtocol.REFRESH_COMMAND)', 'source.getEntity() instanceof ServerPlayer', 'boolean serviceRegistered', 'MinecraftServerAppearanceService.registered(source.getServer())', 'ServerRefreshCommandProtocol.advertised(', 'service.request(source.getPlayerOrException()).admission()', 'ServerRefreshCommandProtocol.result(admission)'],
            serverProfileVerification: ['implements OfficialTextureSignatureVerifier', 'MinecraftSessionService', 'getSecurePropertyValue(property)', 'OfficialTextureAppearanceParser', 'Optional.empty()'],
            serverProfileMutation: ['implements ProfilePropertyAccess', 'currentTextures(ServerPlayer player)', 'installTextures(', 'SignedTexturesProperty', 'CurrentProfileTextures'],
            serverTracking: ['implements ServerTrackingAccess', 'tracked.seenBy', 'tracked.removePlayer(observer)', 'tracked.updatePlayer(observer)', 'scheduleNextTick('],
            serverPlayerInfoPublication: ['implements NativePlayerInfoTransport', 'ClientboundPlayerInfoRemovePacket', 'ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(actors)', 'actors.stream().map(ServerPlayer::getUUID).toList()'],
            serverLoader: ['MinecraftServerLifecycle', 'MinecraftServerRefreshCommand.register']
        ]
        markers.getOrDefault(key, []).each { String marker -> if (!compact.contains(marker)) errors.add("${implementation}: ${key} leaf lacks required marker '${marker}'") }
        if (implementation == 'submission-1.21.11'
                && compact.contains('renderBackground(graphics, mouseX, mouseY, partialTick)')) {
            errors.add('submission-1.21.11: Screen renders its native background twice')
        }
        if (implementation == 'submission-1.21.11') {
            ['Map<String, Minecraft12111SimplePreviewRenderer> bakedRenderers',
             'bakedRenderers.computeIfAbsent(', 'closeMissingBakedRenderers(',
             'Minecraft12111ScrollController', 'NativeWidgetSignature',
             'NativeTabGroup', 'maskWidgetsOutsideClip('].each { String marker ->
                if (!compact.contains(marker)) {
                    errors.add("submission-1.21.11: native host lacks marker '${marker}'")
                }
            }
            if (compact.contains('ViewHostCoordinator')) {
                errors.add('submission-1.21.11: native host must not reuse the common UI coordinator')
            }
            if (compact.contains('setRectangle(')) {
                errors.add('submission-1.21.11: ambiguous 1.21.11 setRectangle argument order is forbidden')
            }
        }
        if (key == 'textures') {
            ['NativePlayerSkinLifecycle', 'OwnedSkinFile'].each { String marker ->
                if (!compact.contains(marker)) errors.add("${implementation}: texture lifecycle lacks required marker '${marker}'")
            }
            List<String> nativeMarkers = implementation == 'identifier-26.2'
                    ? ['SkinTextureDownloader', 'whenComplete', 'stagedFile.close()']
                    : ['HttpTexture', 'NativeTextureUploadTracker', 'closeStagedFile()']
            nativeMarkers.each { String marker ->
                if (!compact.contains(marker)) errors.add("${implementation}: native texture readiness lacks required marker '${marker}'")
            }
        }
        if (key == 'serverSignal' && compact.count('instanceof LiteralCommandNode') != 2) errors.add("${implementation}: server-signal leaf must validate both exact path nodes as LiteralCommandNode")
        if (implementation == 'fabric-server-v1') {
            if (!compact.contains('ServerLifecycleEvents.SERVER_STARTED.register')) errors.add("${implementation}: Fabric service must start after server setup")
            if (compact.contains('ServerLifecycleEvents.SERVER_STARTING.register')) errors.add("${implementation}: Fabric service must not read server services before setup")
            ['ServerPlayConnectionEvents.JOIN.register',
             'MinecraftServerLifecycle.connected(server, handler.player)',
             'ServerPlayConnectionEvents.DISCONNECT.register',
             'MinecraftServerLifecycle.disconnected(server, handler)'].each { String marker ->
                if (!compact.contains(marker)) {
                    errors.add("${implementation}: Fabric connection lifecycle lacks marker '${marker}'")
                }
            }
        }
        if (implementation == 'neoforge-server-v1') {
            ['ServerStartingEvent', 'ServerStoppedEvent',
             'PlayerEvent.PlayerLoggedInEvent',
             'MinecraftServerLifecycle.connected(player.level().getServer(), player)',
             'PlayerEvent.PlayerLoggedOutEvent',
             'MinecraftServerLifecycle.disconnected(player.level().getServer(), player)'].each { String marker ->
                if (!compact.contains(marker)) {
                    errors.add("${implementation}: NeoForge server lifecycle lacks marker '${marker}'")
                }
            }
            if (compact.contains('ServerAboutToStartEvent')) {
                errors.add("${implementation}: NeoForge service must not read the player list before level setup")
            }
        }
    }

    static void verifyRuntimeBoundary(Path root, List<String> errors) {
        ['client-runtime', 'server-contract', 'server-runtime', 'server-vanilla-publication'].each { String module ->
            Path sourceRoot = root.resolve("${module}/src/main/java")
            if (!Files.isDirectory(sourceRoot)) { errors.add("${module} main Java source directory is missing"); return }
            Files.walk(sourceRoot).withCloseable { stream -> stream.filter { Files.isRegularFile(it) && it.toString().endsWith('.java') }.forEach { Path source -> if (PLATFORM_IMPORT.matcher(Files.readString(source)).find()) errors.add("${root.relativize(source)} imports a native platform namespace") } }
        }
    }

    static void verifyPublicationBoundary(Path root, List<String> errors) {
        Map<String, List<String>> sources = [
            'compat/server-common/src/main/java/com/naocraftlab/skins/compat/server/MinecraftServerAppearancePublisher.java': ['new VanillaBatchAppearancePublisher(', 'MinecraftServerConnectionRegistry', 'MinecraftProfilePropertyAccess', 'MinecraftServerTrackingAccess', 'MinecraftPlayerInfoTransport'],
            'compat/capabilities/server/profile-mutation-authlib-v9/src/main/java/com/naocraftlab/skins/compat/server/mixin/GameProfilePropertiesAccessor.java': ['@Mixin(value = GameProfile.class, remap = false)', '@Accessor(value = "properties", remap = false)', '@Mutable']
        ]
        sources.each { String path, List<String> markers ->
            Path file = root.resolve(path)
            if (!Files.isRegularFile(file)) { errors.add("required publication boundary source is missing: ${path}"); return }
            String text = Files.readString(file).replaceAll('\\s+', ' ')
            markers.each { String marker -> if (!text.contains(marker)) errors.add("${path} lacks narrow boundary marker '${marker}'") }
        }
    }

    static Path repositoryFile(Path root, Object raw, String label, List<String> errors) {
        if (!(raw instanceof String) || raw.isBlank()) { errors.add("${label} must be a non-empty repository-relative path"); return null }
        Path candidate = root.resolve(raw).normalize()
        if (!candidate.startsWith(root)) { errors.add("${label} escapes the repository: ${raw}"); return null }
        if (!Files.isRegularFile(candidate)) { errors.add("${label} does not exist: ${raw}"); return null }
        candidate
    }

    static Set<Path> bundleRoots(Path root, Map bundles, String bundleId) {
        Set<Path> roots = [] as Set
        Set<String> visited = [] as Set
        Closure collect
        collect = { String id ->
            if (id == null || !visited.add(id)) return
            Map bundle = bundles[id] instanceof Map ? bundles[id] as Map : [:]
            (bundle.requires ?: []).each { collect(it.toString()) }
            (bundle.java ?: []).each { roots.add(root.resolve(it.toString()).normalize()) }
        }
        collect(bundleId)
        roots
    }

    private SemanticVerifier() {}
}
