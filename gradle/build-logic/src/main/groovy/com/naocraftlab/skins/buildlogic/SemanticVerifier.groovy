package com.naocraftlab.skins.buildlogic

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

final class SemanticVerifier {
    static final Set<String> REQUIRED_KEYS = [
            'gui', 'textures', 'preview', 'appearance', 'loaderScreen', 'session',
            'clientExecutor', 'filePicker', 'bundledSkin', 'currentAppearance',
            'updateNotification', 'skinExtensionEnvironment',
            'serverSignal', 'serverSignalReceiver',
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
        updateNotification: 'update-notification-contract',
        skinExtensionEnvironment: 'skin-extension-environment',
        serverSignal: 'server-refresh-notification',
        serverSignalReceiver: 'server-refresh-reception',
        serverProfileVerification: 'official-server-profile',
        serverProfileMutation: 'vanilla-observer-republication',
        serverTracking: 'vanilla-observer-republication',
        serverPlayerInfoPublication: 'vanilla-observer-republication',
        serverLoader: 'server-loader-lifecycle'
    ]
    static final Map<String, List<String>> SUITE_MARKERS = [
            'view-host-contract'           : ['ViewSpecGoldenTest', 'selectAllOnFocusAcquire', 'ViewNavigationPolicyTest', 'VanillaListSurfaceTest', 'VanillaListSurface.Boundaries', 'compositeCardHovered', 'submitActionId'],
            'texture-ownership-and-normalization': ['TextureRegistryTck', 'PlayerSkinTextureNormalizer', 'NativePlayerSkinLifecycle', 'NativeTextureUploadTracker'],
            'scoped-preview-contract'            : ['PreviewIntent', 'EDITOR_DRAFT', 'EditorPreviewSession', 'EditorPreviewClock', 'ExactLocalPlayerScope', 'CenteredPipPreviewTransform', 'ScreenOwnedRenderTarget', 'PreviewSkinSourceTest'],
            'appearance-orchestration'           : ['AppearanceRefreshCoordinator', 'AppearanceReconnectTracker', 'AppearanceOverrideController', 'deferredReplacementRemainsActiveAndCanAttachWhenPlayerBecomesReady', 'SUPERSEDED', 'DEFERRED'],
            'client-loader-lifecycle'      : ['ClientProcessHost', 'afterReconnect', 'close'],
        'session-boundary': ['SessionValidationService', 'withSession', 'SECRET'],
            'client-executor-contract'     : ['ClientCapabilityContractsTest', 'ClientExecutor'],
        'picker-coordination': ['FilePickerCoordinator', 'concurrent'],
            'resource-pack-access-contract': ['ResourcePackSkinCatalog', 'CatalogGenerationTracker', 'selectedPackMenuRanks'],
        'current-appearance-contract'  : ['CurrentPlayerAppearanceSource', 'currentPlayerAppearance'],
        'update-notification-contract' : [
                'selectorFiltersByExactTargetBeforeChannelAndVersion',
                'redirectTimeoutAndMalformedJsonFailSilentWithoutRetry',
                'contentLengthAndStreamLimitsStopOversizedBodies'],
        'server-refresh-notification': ['ServerAppearanceRefreshNotifier', 'AppearanceRefreshSignalProtocol', 'RemoteAppearanceImpact', 'CONFIRMED_CHANGED', 'confirmedReconciliationStillNotifiesAfterGalleryCloses', 'postMutationLocalFailureStillNotifiesServerWithoutPublishingOutcomeData', 'confirmedPartialReconciliationSignalsExactlyOnce', 'disconnectedConfirmedSignalIsDroppedAndNeverReplayedAfterReconnect', 'readerOrConcurrentLoserWithoutOwnedOutcomeNeverSignals'],
        'server-refresh-reception': ['AppearanceRefreshSignalProtocolTest', 'signalIsVersionedDirectionBoundAndCarriesNoData', 'MinecraftServerAppearanceService', 'oneThousandDistinctSignalsAreAdmittedAndDrainWithoutLocalDrops', 'oneFiveTenAndFiftyChangesPerSecondAllConvergeAfterTheBurst'],
        'official-server-profile': ['OfficialSessionProfileClient', 'OfficialTextureAppearanceParser', 'timestampTransportAndSignatureChangesDoNotChangeTheSemanticKey', 'parsesRetryAfterDeltaAndHttpDateWithSafeFallback', 'mismatchedOfficialIdentityIsRejectedBeforePublication'],
        'vanilla-observer-republication': ['VanillaBatchAppearancePublisher', 'continuesAcrossTicksAndNeverExceedsDeliveryBudget', 'reportsTotalAndMaximumPlatformThreadTimeSeparatelyAcrossTicks', 'semanticCompletionResumesOnFollowingLogicalTickWithoutFreshSameTickBudget', 'retriesFailedRetrackBeforeCompletingAndRestoresExactPair', 'cancelledHeadRetainsRetrackBarrierUntilRecoveryBeforeNextInstall', 'sixtyFourActorBatchKeepsOneRecipientFanoutAcrossOneThousandPlayers', 'watcherChannelRetracksBeforeLargeTabOnlyTail', 'explicitSupersedeFencesAdmittedIntentAndDoesNotPoisonFutureIntent', 'concurrentIntentCannotEnterBetweenLatestCheckAndProfileInstall', 'visibilityPortPreventsProfileDisclosureToHiddenRecipient', 'oneThousandDistinctSignalsAreAdmittedAndDrainWithoutLocalDrops', 'oneFiveTenAndFiftyChangesPerSecondAllConvergeAfterTheBurst', 'reconciliationAttemptsAreBoundedToOnePerFollowingTick', 'successfulWatcherRetryRefreshesWorldPairAfterInitializeFailure'],
        'server-loader-lifecycle': ['eligibilityRequiresOnlineIdentityOrExplicitAttestedProxyOptIn', 'trustedProxyForwarding', 'defaultsMatchThePortableScaleContract', 'sameListenerRegistrationIsIdempotentAndIdentityBound', 'changedAssuranceRotatesGenerationAndSupersedesInFlightTrust', 'reconnectSupersedesOldGenerationAndLateDisconnectCannotRemoveNewBinding'],
        'skin-extension-environment': ['SkinExtensionResourceDetectorTest', 'synthetic failure', 'MALFORMED_EXPRESSIVE_DATA', 'SkinExtensionEnvironment'],
    ]
    static final Pattern PLATFORM_IMPORT = Pattern.compile('(?m)^\\s*import\\s+(?:com\\.mojang\\.authlib(?:\\.|;)|net\\.minecraft(?:\\.|;)|net\\.fabricmc(?:\\.|;)|net\\.neoforged(?:\\.|;)|net\\.minecraftforge(?:\\.|;)|org\\.bukkit(?:\\.|;)|org\\.spongepowered\\.asm(?:\\.|;))')
    static final Pattern VERSION_NAMED_PACKAGE = Pattern.compile(
            '(?m)^\\s*package\\s+[a-zA-Z0-9_.]*\\.(?:mc[0-9]+|v[0-9]+(?:_[0-9]+)+|(?:legacy|paper)[0-9]+)(?:\\.[a-zA-Z0-9_]+)*\\s*;')
    static final Pattern VERSION_NAMED_JAVA_IDENTIFIER = Pattern.compile(
            '\\b[A-Za-z_$][A-Za-z0-9_$]*(?:1201|1211|12111|261|262|263)[A-Za-z0-9_$]*\\b')
    static final Pattern VERSION_NAMED_CODE_ID = Pattern.compile(
            '(?:^|[._-])(?:mc)?(?:1[._-]?20(?:[._-]?1)?|1[._-]?21(?:[._-]?(?:1|11))?|26[._-]?[123]|1201|1211|12111|261|262|263)(?:$|[._-])')

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
            if (key == 'updateNotification') {
                if (abiId != implementation || abiImplementations.containsKey(abiId)) {
                    errors.add("${implementation}: update ABI must be external and exact")
                }
            } else if (abiKind != selected[implementation]) {
                errors.add("${implementation}: ABI kind ${abiKind} does not match catalog kind ${selected[implementation]}")
            }
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
            boolean buildOwnedNativeUpdate = implementation == 'native-static-catalog' &&
                    root.relativize(source).toString().replace('\\', '/') ==
                    'gradle/build-logic/src/main/groovy/com/naocraftlab/skins/buildlogic/MetadataRenderer.groovy'
            if (!buildOwnedNativeUpdate &&
                    (roots.isEmpty() || !roots.any { source.startsWith(it) })) {
                errors.add("${implementation}: leaf source ${root.relativize(source)} is outside its catalog-selected source bundle")
            }
            verifyLeaf(implementation, key, Files.readString(source), errors)
            if (implementation == 'identifier-submission') {
                verifyIdentifierSubmissionGuiBundle(roots, errors)
            }
            if (key == 'preview') verifyPreviewBundle(implementation, roots, errors)
        }
        verifySuites(root, coverage.sharedSuites, usedSuites, errors)
        verifyRuntimeBoundary(root, errors)
        verifyPublicationBoundary(root, errors)
        verifyMixinInjectionPolicy(root, errors)
        verifyCompatibilityReflectionPolicy(root, errors)
        verifyVersionNamespaceScope(root, catalog, errors)
        verifyCatalogCodeIdentifiers(catalog, abi, errors)
        verifyResourceFileNames(root, errors)
        errors
    }

    static void verifyVersionNamespaceScope(Path root, Map catalog, List<String> errors) {
        Files.walk(root).withCloseable { stream ->
            stream.filter { Path source ->
                String relative = root.relativize(source).toString().replace('\\', '/')
                Files.isRegularFile(source) && relative.contains('/src/main/java/') &&
                        relative.endsWith('.java') && !relative.startsWith('build/') &&
                        !relative.contains('/build/') && !relative.startsWith('runs/')
            }.forEach { Path source ->
                verifyVersionNamespace(
                        root.relativize(source).toString().replace('\\', '/'),
                        Files.readString(source),
                        [] as Set<String>,
                        errors)
            }
        }
        verifySourceModuleDirectoryNames(root, errors)
    }

    static void verifySourceModuleDirectoryNames(Path root, List<String> errors) {
        Set<String> moduleDirectories = [] as Set<String>
        [root.resolve('compat'), root.resolve('loader'), root.resolve('server-plugin-adapters')]
                .findAll(Files::isDirectory)
                .each { Path sourceRoot ->
                    Files.walk(sourceRoot).withCloseable { stream ->
                        stream.filter(Files::isRegularFile).forEach { Path source ->
                            String relative = root.relativize(source).toString().replace('\\', '/')
                            int sourceMarker = relative.indexOf('/src/')
                            if (sourceMarker > 0 && !relative.contains('/build/')) {
                                moduleDirectories.add(relative.substring(0, sourceMarker))
                            }
                        }
                    }
                }
        moduleDirectories.sort().each { String relative ->
            verifySourceModuleDirectoryName(relative, errors)
        }
    }

    static void verifySourceModuleDirectoryName(String relative, List<String> errors) {
        verifyCodeIdentifier('source module directory', relative, errors)
    }

    static void verifyVersionNamespace(
            String relative, String text, Set<String> epochs, List<String> errors) {
        if (VERSION_NAMED_PACKAGE.matcher(text).find()) {
            errors.add("${relative}: version-named package is forbidden; use an API-semantic namespace")
        }
        String identifiers = text.replaceAll('(?m)^\\s*package\\s+[^;]+;', '')
        if (VERSION_NAMED_JAVA_IDENTIFIER.matcher(identifiers).find()) {
            errors.add("${relative}: version-named Java identifier is forbidden; use an API-semantic name")
        }
    }

    static void verifyCatalogCodeIdentifiers(Map catalog, Map abi, List<String> errors) {
        (catalog.sourceBundles as Map).keySet().each { Object rawId ->
            verifyCodeIdentifier('source bundle', rawId.toString(), errors)
        }
        (catalog.capabilityImplementations as Map).each { Object rawId, Object rawEntry ->
            verifyCodeIdentifier('capability implementation', rawId.toString(), errors)
            if (rawEntry instanceof Map) {
                verifyCodeIdentifier(
                        "${rawId}.abiImplementation",
                        rawEntry.abiImplementation?.toString(),
                        errors)
            }
        }
        (abi.implementations as Map).keySet().each { Object rawId ->
            verifyCodeIdentifier('ABI implementation', rawId.toString(), errors)
        }
        catalog.targets.each { Map target ->
            verifyCodeIdentifier(
                    "${target.id}.automaticModuleName",
                    target.artifact.automaticModuleName?.toString(),
                    errors)
            ((target.metadata.mixins ?: []) + (target.metadata.serverMixins ?: []))
                    .each { Object rawMixin ->
                        verifyCodeIdentifier("${target.id}.mixin", rawMixin.toString(), errors)
                    }
        }
    }

    static void verifyResourceFileNames(Path root, List<String> errors) {
        Files.walk(root).withCloseable { stream ->
            stream.filter { Path resource ->
                String relative = root.relativize(resource).toString().replace('\\', '/')
                Files.isRegularFile(resource) && relative.contains('/src/main/resources/') &&
                        !relative.contains('/build/')
            }.forEach { Path resource ->
                verifyCodeIdentifier(
                        root.relativize(resource).toString().replace('\\', '/'),
                        resource.fileName.toString(),
                        errors)
            }
        }
    }

    static void verifyCodeIdentifier(String owner, String identifier, List<String> errors) {
        if (identifier != null && VERSION_NAMED_CODE_ID.matcher(identifier).find()) {
            errors.add("${owner}: Minecraft-version code identifier '${identifier}' is forbidden")
        }
    }

    static void verifyMixinInjectionPolicy(Path root, List<String> errors) {
        [root.resolve('compat'), root.resolve('loader'), root.resolve('targets')]
                .findAll(Files::isDirectory)
                .each { Path sourceRoot ->
                    Files.walk(sourceRoot).withCloseable { stream ->
                        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith('Mixin.java') }
                                .forEach { Path source ->
                                    String text = Files.readString(source)
                                    String relative = root.relativize(source).toString().replace('\\', '/')
                                    if (text.contains('@Redirect')) {
                                        errors.add("${relative}: production @Redirect is forbidden; use a chainable MixinExtras wrapper")
                                    }
                                    if (text ==~ /(?s).*\bordinal\s*=.*/) {
                                        errors.add("${relative}: ordinal-based Mixin anchors are forbidden")
                                    }
                                    if (text ==~ /(?s).*\brequire\s*=\s*0\b.*/) {
                                        errors.add("${relative}: optional require=0 Mixin injection is forbidden")
                                    }
                                    int chainableWrappers = text.count('@WrapOperation') +
                                            text.count('@WrapMethod')
                                    if (chainableWrappers > text.count('original.call(')) {
                                        errors.add("${relative}: every chainable wrapper must delegate through original.call")
                                    }
                                    if (text.contains('@ModifyReturnValue') &&
                                            !(text ==~ /(?s).*\boriginal\b.*/)) {
                                        errors.add("${relative}: return modifier must preserve an explicit original value")
                                    }
                                }
                    }
                }
    }

    static void verifyCompatibilityReflectionPolicy(Path root, List<String> errors) {
        Set<String> classLoadingLeaves = [
                'client-runtime/src/main/java/com/naocraftlab/skins/runtime/SqliteSupport.java',
                'server-plugin-bukkit/src/main/java/com/naocraftlab/skins/server/plugin/bukkit/BukkitRuntimeDetector.java',
                'server-plugin-bukkit/src/main/java/com/naocraftlab/skins/server/plugin/bukkit/ExactAuthlibSignatureVerifier.java',
                'server-plugin-bukkit/src/main/java/com/naocraftlab/skins/server/plugin/bukkit/ExactLegacyPublicationBackend.java',
                'server-plugin-bukkit/src/main/java/com/naocraftlab/skins/server/plugin/bukkit/PaperProfilePublicationBackend.java',
                'server-plugin-bukkit/src/main/java/com/naocraftlab/skins/server/plugin/bukkit/PaperProfileStateBinding.java',
                'server-plugin-bukkit/src/main/java/com/naocraftlab/skins/server/plugin/bukkit/PaperConnectionAssuranceBinding.java'
        ] as Set
        Files.walk(root).withCloseable { stream ->
            stream.filter { Path source ->
                Files.isRegularFile(source) && source.toString().endsWith('.java') &&
                        source.toString().contains('/src/main/') &&
                        !source.startsWith(root.resolve('gradle/build-logic'))
            }.forEach { Path source ->
                String text = Files.readString(source)
                String relative = root.relativize(source).toString().replace('\\', '/')
                if (relative.startsWith('.') || relative.startsWith('runs/') ||
                        relative.startsWith('build/')) return
                if (text ==~ /(?s).*\bget(?:Declared)?(?:Methods|Fields)\s*\(.*/) {
                    errors.add("${relative}: compatibility discovery by member enumeration is forbidden")
                }
                if (text.contains('Class.forName(') && !classLoadingLeaves.contains(relative)) {
                    errors.add("${relative}: Class.forName is allowed only in named exact binding factories, runtime detection, or SqliteSupport")
                }
            }
        }
    }

    static void verifyIdentifierSubmissionGuiBundle(Set<Path> roots, List<String> errors) {
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
                errors.add("identifier-submission: native icon/scroll host lacks required marker '${required}'")
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
        if (implementation.startsWith('avatar-pip-submission-')) {
            ['submitEntityRenderState', 'submitSkinRenderState',
             'LivingEntityRendererPreviewMixin',
             'EntityRenderState state',
             'PlayerSkin.insecure', 'CenteredPlayerPreviewGeometry.centeredEntityTranslation(',
             'SimplePreviewRenderer', 'ItemStack.EMPTY',
             'BakedPreviewRenderState',
             'BakedPreviewSubmission', 'GuiGraphicsPreviewMixin',
             'LivePreviewRenderState',
             'LivePreviewSubmission',
             'LivePreviewRenderer',
             'PreviewContext', 'PreviewScope',
             'state.previewContext().open(minecraft)', 'EditorPreviewLayerGuard.open(',
             'PreviewModelAnchors', 'ModelPartPreviewMixin',
             'renderPlayer.tickCount =', 'renderPlayer.avatarState().tick(',
             '.extractEntity(', '.submit(', 'renderAllFeatures()',
             'ScreenOwnedRenderTarget', 'standaloneEquipment',
             'PlayerCapeModel', 'ElytraModel', 'ELYTRA_ROT_X', 'ELYTRA_ROT_Z',
             'ElytraModel.createLayer().bakeRoot()', 'Model<?> attachmentModel',
             'state.elytraRotX = CenteredPipPreviewTransform.ELYTRA_ROT_X',
             'state.elytraRotY = CenteredPipPreviewTransform.ELYTRA_ROT_Y',
             'state.elytraRotZ = CenteredPipPreviewTransform.ELYTRA_ROT_Z',
             'CenteredPipPreviewTransform.modelPitchRadians(state.pitchDegrees())',
             'CenteredPipPreviewTransform.applyPlayerPose('].each { String required ->
                if (!text.contains(required)) {
                    errors.add("${implementation}: submission preview lacks required marker (${required})")
                }
            }
            if (text.contains('LivingEntityRenderState state')) {
                errors.add("${implementation}: layer redirect must match the erased EntityRenderState descriptor")
            }
        } else if (implementation.startsWith('avatar-pip-')) {
            ['AvatarPreviewContext', 'NclBakedPlayerRenderState',
             'NclBakedPlayerSubmission', 'GuiGraphicsExtractorPreviewMixin',
             'ScreenOwnedRenderTarget', 'NclBakedPlayerTarget',
             'standaloneEquipment', 'PlayerCapeModel', 'ElytraModel',
             'ELYTRA_ROT_X', 'ELYTRA_ROT_Z'].each { String required ->
                if (!text.contains(required)) {
                    errors.add("${implementation}: extraction preview lacks deferred/composite marker (${required})")
                }
            }
            List<String> pitchMarkers = [
                    'BakedPlayerPose.applyPitch(pose, state.pitchDegrees())',
                    'CenteredPlayerPreviewGeometry.centeredEntityTranslation(',
                    'CenteredPipPreviewTransform.modelPitchRadians(pitchDegrees)',
                    'return CenteredPipPreviewTransform.pitchRadians(pitchDegrees)'
            ]
            pitchMarkers.each { String required ->
                if (!text.contains(required)) {
                    errors.add("${implementation}: extraction preview lacks live/baked pitch split marker (${required})")
                }
            }
            if (text.contains('modelView.rotateX(')) {
                errors.add("${implementation}: extraction preview pitch must stay in the centered submitted pose")
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
        if (implementation.startsWith('avatar-pip-')) {
            if (implementation.endsWith('-fabric')) {
                List<String> registrationMarkers = implementation.startsWith('avatar-pip-submission-')
                        ? ['GuiRendererMixin', '@ModifyExpressionValue', 'buildOrThrow',
                           'BakedPreviewRenderer(bufferSource)',
                           'LivePreviewRenderer(bufferSource)']
                        : ['PictureInPictureRendererRegistry.register',
                           'new NclBakedPlayerRenderer(']
                registrationMarkers.each { String required ->
                    if (!text.contains(required)) {
                        errors.add("${implementation}: Fabric preview lacks native registration marker (${required})")
                    }
                }
            } else if (implementation.endsWith('-neoforge')) {
                ['RegisterPictureInPictureRenderersEvent', 'event.register('].each { String required ->
                    if (!text.contains(required)) {
                        errors.add("${implementation}: NeoForge preview lacks native registration marker (${required})")
                    }
                }
                if (implementation.startsWith('avatar-pip-submission-')
                        && !text.contains('LivePreviewRenderer::new')) {
                    errors.add("${implementation}: NeoForge preview lacks native live registration")
                }
            } else {
                errors.add("${implementation}: PIP preview implementation must identify its loader")
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
            filePicker: ['implements FilePicker', 'FilePickerCoordinator', 'new FilePickerCoordinator(', 'COORDINATOR.choose'],
                bundledSkin      : ['implements SkinCatalogSource'],
                currentAppearance: ['implements CurrentPlayerAppearanceSource'],
            serverSignal: ['implements ServerAppearanceRefreshNotifier', 'activeConnectionGeneration()', 'requestOfficialProfileRefresh()', 'currentConnection()', 'AppearanceRefresh'],
            serverSignalReceiver: ['MinecraftAppearanceRefreshNetwork', 'MinecraftServerAppearanceService', '.request('],
            serverProfileVerification: ['implements OfficialTextureSignatureVerifier', 'getSecurePropertyValue(property)', 'OfficialTextureAppearanceParser', 'Optional.empty()'],
            serverProfileMutation: ['implements ProfilePropertyAccess', 'currentTextures(ServerPlayer player)', 'installTextures(', 'SignedTexturesProperty', 'CurrentProfileTextures'],
            serverTracking: ['implements ServerTrackingAccess', 'tracked.seenBy', 'tracked.removePlayer(observer)', 'tracked.updatePlayer(observer)', 'scheduleNextTick('],
            serverPlayerInfoPublication: ['implements NativePlayerInfoTransport', 'ClientboundPlayerInfoRemovePacket', 'ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(actors)', 'actors.stream().map(ServerPlayer::getUUID).toList()'],
            serverLoader: ['MinecraftServerLifecycle', 'MinecraftAppearanceRefreshNetwork']
        ]
        markers.getOrDefault(key, []).each { String marker -> if (!compact.contains(marker)) errors.add("${implementation}: ${key} leaf lacks required marker '${marker}'") }
        if (implementation == 'modmenu-default-index') {
            if (!compact.contains('implements ModMenuApi') ||
                    !compact.contains('getModConfigScreenFactory()') ||
                    compact.contains('getUpdateChecker()')) {
                errors.add("${implementation}: legacy Mod Menu leaf must preserve only the default update index")
            }
        }
        if (implementation == 'modmenu-static-catalog') {
            ['implements ModMenuApi', 'getUpdateChecker()', 'UpdateCatalogClient.create()',
             '.orElse(null)'].each { String marker ->
                if (!compact.contains(marker)) {
                    errors.add("${implementation}: modern Mod Menu leaf lacks '${marker}'")
                }
            }
            if (compact.contains('getUpdateMessage()')) {
                errors.add("${implementation}: modern Mod Menu leaf must preserve default update message ABI")
            }
        }
        if (implementation == 'native-static-catalog' &&
                (!compact.contains('nativeUpdatesUrl') ||
                        !compact.contains('updateJSONURL'))) {
            errors.add("${implementation}: native static catalog metadata leaf is incomplete")
        }
        if (key == 'serverProfileVerification') {
            String sessionServiceType = implementation == 'profile-verification-authlib-v10'
                    ? 'SessionService'
                    : 'MinecraftSessionService'
            if (!compact.contains(sessionServiceType)) {
                errors.add("${implementation}: ${key} leaf lacks required marker '${sessionServiceType}'")
            }
        }
        if (implementation == 'identifier-submission'
                && compact.contains('renderBackground(graphics, mouseX, mouseY, partialTick)')) {
            errors.add('identifier-submission: Screen renders its native background twice')
        }
        if (implementation == 'identifier-submission') {
            ['Map<String, SimplePreviewRenderer> bakedRenderers',
             'bakedRenderers.computeIfAbsent(', 'closeMissingBakedRenderers(',
             'SubmissionScrollController', 'NativeWidgetSignature',
             'NativeTabGroup', 'maskWidgetsOutsideClip('].each { String marker ->
                if (!compact.contains(marker)) {
                    errors.add("identifier-submission: native host lacks marker '${marker}'")
                }
            }
            if (compact.contains('ViewHostCoordinator')) {
                errors.add('identifier-submission: native host must not reuse the common UI coordinator')
            }
            if (compact.contains('setRectangle(')) {
                errors.add('identifier-submission: ambiguous 1.21.11 setRectangle argument order is forbidden')
            }
        }
        if (implementation == 'immediate-resource-location-player-info') {
            ['panel.style() == ViewSpec.Panel.Style.VANILLA_LIST',
             'graphics.fill(', '0x80000000'].each { String marker ->
                if (!compact.contains(marker)) {
                    errors.add("${implementation}: legacy gallery cards lack translucent black surface marker '${marker}'")
                }
            }
            if (compact.contains('ViewSpec.Panel.Style.VANILLA_LIST ? texture')) {
                errors.add("${implementation}: legacy gallery cards must not sample the dirt background")
            }
        }
        if (implementation == 'identifier-extraction-menu-tab-input-constants'
                && (!compact.contains('isEnterKey(event.shortcutKey())')
                        || !compact.contains('switch (event.shortcutKey())')
                        || compact.contains('isEnterKey(event.key())')
                        || compact.contains('switch (event.key())'))) {
            errors.add('identifier-extraction-menu-tab-input-constants: product navigation must classify logical shortcutKey rather than physical scancode')
        }
        if ((implementation == 'identifier-submission'
                || implementation.startsWith('identifier-extraction-'))
                && !compact.contains('CatalogCardStyle.backgroundBehindContentColor(')) {
            errors.add("${implementation}: card hover/selection fill must render in the background pass")
        }
        if (implementation == 'identifier-submission'
                && !compact.contains('clipped(graphics, current, panel.id(), () ->')) {
            errors.add('identifier-submission: VANILLA_LIST panels must use the card viewport clip')
        }
        if ((implementation == 'identifier-submission'
                || implementation.startsWith('identifier-extraction-'))
                && !compact.contains('VanillaListSurface.boundaries(')) {
            errors.add("${implementation}: modern card workspace lacks target-native boundary geometry")
        }
        if (key == 'textures') {
            ['NativePlayerSkinLifecycle', 'OwnedSkinFile'].each { String marker ->
                if (!compact.contains(marker)) errors.add("${implementation}: texture lifecycle lacks required marker '${marker}'")
            }
            List<String> nativeMarkers = implementation == 'identifier-texture-registry'
                    ? ['SkinTextureDownloader', 'whenComplete', 'stagedFile.close()']
                    : ['HttpTexture', 'NativeTextureUploadTracker', 'closeStagedFile()']
            nativeMarkers.each { String marker ->
                if (!compact.contains(marker)) errors.add("${implementation}: native texture readiness lacks required marker '${marker}'")
            }
        }
        if ((key == 'serverSignal' || key == 'serverSignalReceiver')
                && (compact.contains('sendCommand(') || compact.contains('RegisterCommandsEvent')
                || compact.contains('CommandRegistrationCallback'))) {
            errors.add("${implementation}: appearance refresh transport must not expose commands")
        }
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
            'compat/capabilities/server/profile-mutation-authlib-v9/src/main/java/com/naocraftlab/skins/compat/server/MinecraftProfilePropertyAccess.java': ['new GameProfile(', 'PlayerGameProfileAccessor', 'nclskins$setGameProfile(replacementProfile)'],
            'compat/capabilities/server/profile-mutation-authlib-v9/src/main/java/com/naocraftlab/skins/compat/server/mixin/PlayerGameProfileAccessor.java': ['@Mixin(Player.class)', '@Accessor("gameProfile")', '@Mutable']
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
