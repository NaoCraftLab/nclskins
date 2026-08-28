package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.PersonalSkinEntry;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.storage.StoredAsset;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


public final class LibraryService {
    private final NclSkinsStorage storage;
    private final Clock clock;
    private final PngValidator pngValidator = new PngValidator();

    public LibraryService(NclSkinsStorage storage, Clock clock) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AccountState load(UUID accountId) throws IOException {
        return storage.loadOrCreateAccount(accountId);
    }

    public ImportedSkin importSkin(
            UUID accountId,
            String name,
            SkinVariant variant,
            SkinSource source,
            byte[] pngBytes) throws IOException, PngValidationException {
        return importSkin(accountId, name, variant, source, pngBytes, Optional.empty());
    }

    public ImportedSkin importSkin(
            UUID accountId,
            String name,
            SkinVariant variant,
            SkinSource source,
            byte[] pngBytes,
            Optional<CatalogOrigin> catalogOrigin) throws IOException, PngValidationException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(catalogOrigin, "catalogOrigin");
        StoredAsset stored = storage.storeAsset(pngBytes);
        Instant now = clock.instant();
        SkinAsset asset = new SkinAsset(
                UUID.randomUUID(), name, stored.sha256(), variant, source, now, now, catalogOrigin);
        boolean managedSnapshot = source == SkinSource.VANILLA_DEFAULT || source == SkinSource.CURRENT_OFFICIAL;
        AccountState state = storage.updateAccount(accountId, current -> {
            boolean alreadyIndexed = managedSnapshot && current.skinAssets().stream().anyMatch(existing ->
                    existing.source() == source
                            && existing.sha256().equals(stored.sha256())
                            && existing.variant() == variant
                            && existing.catalogOrigin().equals(catalogOrigin));
            if (alreadyIndexed) {
                return current;
            }
            return new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    accountId,
                    appended(current.skinAssets(), asset),
                    current.personalSkins(),
                    current.presets(),
                    nextRevision(current, now));
        });
        SkinAsset indexed = managedSnapshot
                ? state.skinAssets().stream()
                        .filter(existing -> existing.source() == source
                                && existing.sha256().equals(stored.sha256())
                                && existing.variant() == variant
                                && existing.catalogOrigin().equals(catalogOrigin))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Managed skin snapshot was not indexed"))
                : asset;
        return new ImportedSkin(state, indexed, stored);
    }


    public SavedImportedPreset savePresetWithImportedSkin(
            UUID accountId,
            Optional<UUID> originalPresetId,
            String presetName,
            String assetName,
            SkinVariant variant,
            SkinSource source,
            byte[] pngBytes,
            CatalogOrigin catalogOrigin,
            String capeId) throws IOException, PngValidationException {
        return savePresetWithImportedSkin(
                accountId, originalPresetId, presetName, assetName, variant, source, pngBytes,
                catalogOrigin, OuterLayerVisibility.allVisible(), capeId);
    }


    public SavedImportedPreset savePresetWithImportedSkin(
            UUID accountId,
            Optional<UUID> originalPresetId,
            String presetName,
            String assetName,
            SkinVariant variant,
            SkinSource source,
            byte[] pngBytes,
            CatalogOrigin catalogOrigin,
            OuterLayerVisibility outerLayerVisibility,
            String capeId) throws IOException, PngValidationException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(originalPresetId, "originalPresetId");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(catalogOrigin, "catalogOrigin");
        Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
        byte[] requiredPng = Objects.requireNonNull(pngBytes, "pngBytes");
        String renderSha256 = pngValidator.renderSha256(requiredPng);
        StoredAsset stored = storeOrReusePersonalAsset(accountId, requiredPng, renderSha256);
        StoredAsset[] selectedStored = {stored};
        UUID assetId = UUID.randomUUID();
        UUID createdPresetId = UUID.randomUUID();
        Instant now = clock.instant();
        AccountState state = storage.updateAccount(accountId, current -> {
            StoredAsset mutationStored = recheckPersonalAssetReuse(
                    current, renderSha256, selectedStored[0]);
            selectedStored[0] = mutationStored;
            int presetIndex = originalPresetId
                    .map(presetId -> indexOfPreset(current.presets(), presetId))
                    .orElse(-1);
            Instant mutationTime = nextRevision(current, now);
            SkinAsset asset = new SkinAsset(
                    assetId,
                    assetName,
                    mutationStored.sha256(),
                    variant,
                    source,
                    mutationTime,
                    mutationTime,
                    Optional.of(catalogOrigin));
            List<SkinAsset> assets = appended(current.skinAssets(), asset);
            List<AppearancePreset> presets = new ArrayList<>(current.presets());
            AppearancePreset preset;
            if (presetIndex < 0) {
                preset = new AppearancePreset(
                        createdPresetId,
                        presetName,
                        SkinReference.asset(asset.id()),
                        capeId,
                        outerLayerVisibility,
                        mutationTime,
                        mutationTime);
                presets.add(preset);
            } else {
                AppearancePreset existing = presets.get(presetIndex);
                preset = new AppearancePreset(
                        existing.id(),
                        presetName,
                        SkinReference.asset(asset.id()),
                        capeId,
                        outerLayerVisibility,
                        existing.createdAt(),
                        mutationTime);
                presets.set(presetIndex, preset);
            }
            return new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    current.accountId(),
                    assets,
                    current.personalSkins(),
                    presets,
                    mutationTime);
        });
        UUID presetId = originalPresetId.orElse(createdPresetId);
        return new SavedImportedPreset(
                state,
                findPreset(state, presetId),
                findSkin(state, assetId),
                selectedStored[0]);
    }


    public SavedPersonalSkinPreset savePresetWithPersonalSkin(
            UUID accountId,
            Optional<UUID> originalPresetId,
            String presetName,
            String personalSkinDisplayName,
            SkinVariant variant,
            PersonalSkinSource source,
            byte[] pngBytes,
            String capeId) throws IOException, PngValidationException {
        return savePresetWithPersonalSkin(
                accountId, originalPresetId, presetName, personalSkinDisplayName, variant, source,
                pngBytes, OuterLayerVisibility.allVisible(), capeId);
    }

    public SavedPersonalSkinPreset savePresetWithPersonalSkin(
            UUID accountId,
            Optional<UUID> originalPresetId,
            String presetName,
            String personalSkinDisplayName,
            SkinVariant variant,
            PersonalSkinSource source,
            byte[] pngBytes,
            OuterLayerVisibility outerLayerVisibility,
            String capeId) throws IOException, PngValidationException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(originalPresetId, "originalPresetId");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
        String displayName = requirePersonalSkinDisplayName(personalSkinDisplayName);
        byte[] requiredPng = Objects.requireNonNull(pngBytes, "pngBytes");
        String renderSha256 = pngValidator.renderSha256(requiredPng);
        StoredAsset stored = storeOrReusePersonalAsset(accountId, requiredPng, renderSha256);
        StoredAsset[] selectedStored = {stored};
        UUID candidateAssetId = UUID.randomUUID();
        UUID createdPresetId = UUID.randomUUID();
        Instant now = clock.instant();

        AccountState state = storage.updateAccount(accountId, current -> {
            StoredAsset mutationStored = recheckPersonalAssetReuse(
                    current, renderSha256, selectedStored[0]);
            selectedStored[0] = mutationStored;
            int presetIndex = originalPresetId
                    .map(presetId -> indexOfPreset(current.presets(), presetId))
                    .orElse(-1);
            PersonalSkinEntry existingEntry = current.personalSkins().stream()
                    .filter(entry -> entry.sha256().equals(mutationStored.sha256()))
                    .findFirst()
                    .orElse(null);
            Instant mutationTime = nextRevision(current, now);
            if (existingEntry != null && !mutationTime.isAfter(existingEntry.updatedAt())) {
                mutationTime = existingEntry.updatedAt().plusNanos(1L);
            }

            SkinAsset asset = reusablePersonalAsset(
                    current, existingEntry, mutationStored.sha256(), variant)
                    .orElse(null);
            List<SkinAsset> assets = current.skinAssets();
            if (asset == null) {
                String assetName = existingEntry != null && existingEntry.visible()
                        ? existingEntry.displayName()
                        : displayName;
                asset = new SkinAsset(
                        candidateAssetId,
                        assetName,
                        mutationStored.sha256(),
                        variant,
                        SkinSource.IMPORTED,
                        mutationTime,
                        mutationTime);
                assets = appended(assets, asset);
            }

            PersonalSkinEntry personalSkin;
            if (existingEntry == null) {
                personalSkin = new PersonalSkinEntry(
                        mutationStored.sha256(),
                        displayName,
                        source,
                        mutationTime,
                        mutationTime,
                        Map.of(variant, asset.id()),
                        true);
            } else if (existingEntry.visible()) {
                personalSkin = existingEntry
                        .withVariant(variant, asset.id(), mutationTime)
                        .withSourcePriority(source, mutationTime);
            } else {
                PersonalSkinSource restoredSource = existingEntry.source()
                        == PersonalSkinSource.PLAYER_NAME
                        ? PersonalSkinSource.PLAYER_NAME
                        : source;
                personalSkin = existingEntry.restored(
                        displayName, restoredSource, variant, asset.id(), mutationTime);
            }
            List<PersonalSkinEntry> personalSkins = replacePersonalSkin(
                    current.personalSkins(), existingEntry, personalSkin);

            List<AppearancePreset> presets = new ArrayList<>(current.presets());
            AppearancePreset preset;
            if (presetIndex < 0) {
                preset = new AppearancePreset(
                        createdPresetId,
                        presetName,
                        SkinReference.asset(asset.id()),
                        capeId,
                        outerLayerVisibility,
                        mutationTime,
                        mutationTime);
                presets.add(preset);
            } else {
                AppearancePreset existingPreset = presets.get(presetIndex);
                preset = new AppearancePreset(
                        existingPreset.id(),
                        presetName,
                        SkinReference.asset(asset.id()),
                        capeId,
                        outerLayerVisibility,
                        existingPreset.createdAt(),
                        mutationTime);
                presets.set(presetIndex, preset);
            }
            return new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    current.accountId(),
                    assets,
                    personalSkins,
                    presets,
                    mutationTime);
        });

        PersonalSkinEntry personalSkin = findPersonalSkin(state, selectedStored[0].sha256());
        SkinAsset asset = findSkin(
                state,
                personalSkin.optionalAssetId(variant)
                        .orElseThrow(() -> new IllegalStateException("Saved personal variant is missing")));
        UUID presetId = originalPresetId.orElse(createdPresetId);
        return new SavedPersonalSkinPreset(
                state, findPreset(state, presetId), personalSkin, asset, selectedStored[0]);
    }

    public SavedPersonalSkinPreset createPresetFromPersonalSkin(
            UUID accountId,
            String presetName,
            String personalSkinDisplayName,
            SkinVariant variant,
            PersonalSkinSource source,
            byte[] pngBytes,
            String capeId) throws IOException, PngValidationException {
        return savePresetWithPersonalSkin(
                accountId,
                Optional.empty(),
                presetName,
                personalSkinDisplayName,
                variant,
                source,
                pngBytes,
                OuterLayerVisibility.allVisible(),
                capeId);
    }

    public SavedPersonalSkinPreset updatePresetFromPersonalSkin(
            UUID accountId,
            UUID presetId,
            String presetName,
            String personalSkinDisplayName,
            SkinVariant variant,
            PersonalSkinSource source,
            byte[] pngBytes,
            String capeId) throws IOException, PngValidationException {
        return savePresetWithPersonalSkin(
                accountId,
                Optional.of(Objects.requireNonNull(presetId, "presetId")),
                presetName,
                personalSkinDisplayName,
                variant,
                source,
                pngBytes,
                OuterLayerVisibility.allVisible(),
                capeId);
    }


    public BatchPersonalSkinPresetImport importPersonalSkinPresets(
            UUID accountId,
            List<PersonalSkinPresetImport> imports) throws IOException, PngValidationException {
        Objects.requireNonNull(accountId, "accountId");
        List<PersonalSkinPresetImport> requested = List.copyOf(
                Objects.requireNonNull(imports, "imports"));
        if (requested.isEmpty()) {
            return new BatchPersonalSkinPresetImport(load(accountId), 0, 0);
        }

        List<PreparedPersonalSkinPresetImport> prepared = new ArrayList<>(requested.size());
        Map<String, StoredAsset> canonicalAssets = new java.util.LinkedHashMap<>();
        for (PersonalSkinPresetImport candidate : requested) {
            Objects.requireNonNull(candidate, "imports contains null");
            String renderSha256 = pngValidator.renderSha256(candidate.pngBytes());
            StoredAsset stored = canonicalAssets.get(renderSha256);
            if (stored == null) {
                stored = storeOrReusePersonalAsset(
                        accountId, candidate.pngBytes(), renderSha256);
                canonicalAssets.put(renderSha256, stored);
            }
            prepared.add(new PreparedPersonalSkinPresetImport(
                    candidate, stored, renderSha256));
        }

        Instant now = clock.instant();
        int[] imported = {0};
        int[] alreadyPresent = {0};
        AccountState state = storage.updateAccount(accountId, current -> {
            imported[0] = 0;
            alreadyPresent[0] = 0;
            Instant mutationTime = nextRevision(current, now);
            List<SkinAsset> assets = new ArrayList<>(current.skinAssets());
            List<PersonalSkinEntry> personalSkins = new ArrayList<>(current.personalSkins());
            List<AppearancePreset> presets = new ArrayList<>(current.presets());
            boolean changed = false;

            for (PreparedPersonalSkinPresetImport item : prepared) {
                PersonalSkinPresetImport candidate = item.candidate();
                StoredAsset mutationStored = recheckPersonalAssetReuse(
                        current, item.renderSha256(), item.stored());
                String sha256 = mutationStored.sha256();
                PersonalSkinEntry existingEntry = personalSkins.stream()
                        .filter(entry -> entry.sha256().equals(sha256))
                        .findFirst()
                        .orElse(null);
                SkinAsset asset = reusablePersonalAsset(
                        assets, existingEntry, sha256, candidate.variant()).orElse(null);
                if (asset == null) {
                    asset = new SkinAsset(
                            UUID.randomUUID(),
                            existingEntry != null && existingEntry.visible()
                                    ? existingEntry.displayName()
                                    : candidate.personalSkinDisplayName(),
                            sha256,
                            candidate.variant(),
                            SkinSource.IMPORTED,
                            mutationTime,
                            mutationTime);
                    assets.add(asset);
                    changed = true;
                }

                PersonalSkinEntry replacement;
                if (existingEntry == null) {
                    replacement = new PersonalSkinEntry(
                            sha256,
                            candidate.personalSkinDisplayName(),
                            candidate.source(),
                            mutationTime,
                            mutationTime,
                            Map.of(candidate.variant(), asset.id()),
                            true);
                } else if (existingEntry.visible()) {
                    replacement = existingEntry
                            .withVariant(candidate.variant(), asset.id(), mutationTime)
                            .withSourcePriority(candidate.source(), mutationTime);
                } else {
                    PersonalSkinSource restoredSource = existingEntry.source()
                            == PersonalSkinSource.PLAYER_NAME
                            ? PersonalSkinSource.PLAYER_NAME
                            : candidate.source();
                    replacement = existingEntry.restored(
                            candidate.personalSkinDisplayName(),
                            restoredSource,
                            candidate.variant(),
                            asset.id(),
                            mutationTime);
                }
                if (!replacement.equals(existingEntry)) {
                    replacePersonalSkinInPlace(personalSkins, existingEntry, replacement);
                    changed = true;
                }

                if (containsEquivalentPreset(
                        presets,
                        assets,
                        candidate.presetName(),
                        sha256,
                        candidate.variant(),
                        candidate.capeId())) {
                    alreadyPresent[0]++;
                    continue;
                }
                presets.add(new AppearancePreset(
                        UUID.randomUUID(),
                        candidate.presetName(),
                        SkinReference.asset(asset.id()),
                        candidate.capeId(),
                        OuterLayerVisibility.allVisible(),
                        mutationTime,
                        mutationTime));
                imported[0]++;
                changed = true;
            }

            if (!changed) {
                return current;
            }
            return new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    current.accountId(),
                    List.copyOf(assets),
                    List.copyOf(personalSkins),
                    List.copyOf(presets),
                    mutationTime);
        });
        return new BatchPersonalSkinPresetImport(state, imported[0], alreadyPresent[0]);
    }


    public AccountState hidePersonalSkin(UUID accountId, String sha256) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        String requiredHash = requireSha256(sha256);
        Instant now = clock.instant();
        return storage.updateAccount(accountId, current -> {
            PersonalSkinEntry existing = findPersonalSkin(current, requiredHash);
            if (!existing.visible()) {
                return current;
            }
            Instant mutationTime = nextRevision(current, now);
            if (!mutationTime.isAfter(existing.updatedAt())) {
                mutationTime = existing.updatedAt().plusNanos(1L);
            }
            PersonalSkinEntry hidden = existing.hidden(mutationTime);
            return new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    current.accountId(),
                    current.skinAssets(),
                    replacePersonalSkin(current.personalSkins(), existing, hidden),
                    current.presets(),
                    mutationTime);
        });
    }

    public PersonalSkinEntry findPersonalSkin(AccountState state, String sha256) {
        Objects.requireNonNull(state, "state");
        String requiredHash = requireSha256(sha256);
        return state.personalSkins().stream()
                .filter(entry -> entry.sha256().equals(requiredHash))
                .findFirst()
                .orElseThrow(() -> new LibraryOperationException(
                        LibraryOperationException.Code.PERSONAL_SKIN_NOT_FOUND,
                        "Personal skin was not found."));
    }


    public AccountState renamePersonalSkin(UUID accountId, String sha256, String newName) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        String requiredHash = requireSha256(sha256);
        Instant now = clock.instant();
        return storage.updateAccount(accountId, current -> {
            PersonalSkinEntry existing = findPersonalSkin(current, requiredHash);
            Instant mutationTime = nextRevision(current, now);
            if (!mutationTime.isAfter(existing.updatedAt())) {
                mutationTime = existing.updatedAt().plusNanos(1L);
            }
            PersonalSkinEntry renamed = existing.renamed(newName, mutationTime);
            return new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    current.accountId(),
                    current.skinAssets(),
                    replacePersonalSkin(current.personalSkins(), existing, renamed),
                    current.presets(),
                    mutationTime);
        });
    }


    public Optional<SkinAsset> findVisiblePersonalSkinAsset(
            AccountState state, String sha256, SkinVariant variant) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(variant, "variant");
        String requiredHash = requireSha256(sha256);
        return state.personalSkins().stream()
                .filter(PersonalSkinEntry::visible)
                .filter(entry -> entry.sha256().equals(requiredHash))
                .findFirst()
                .flatMap(entry -> entry.optionalAssetId(variant))
                .flatMap(assetId -> state.skinAssets().stream()
                        .filter(asset -> asset.id().equals(assetId))
                        .findFirst());
    }

    public Optional<SkinAsset> findVisiblePersonalSkinAsset(
            UUID accountId, String sha256, SkinVariant variant) throws IOException {
        return findVisiblePersonalSkinAsset(load(accountId), sha256, variant);
    }

    public AccountState renameSkin(UUID accountId, UUID skinId, String newName) throws IOException {
        Instant now = clock.instant();
        return storage.updateAccount(accountId, current -> {
            List<SkinAsset> assets = replaceSkin(current.skinAssets(), skinId, asset -> asset.renamed(newName, now));
            return copy(current, assets, current.presets(), now);
        });
    }

    public AccountState changeSkinVariant(UUID accountId, UUID skinId, SkinVariant variant) throws IOException {
        Objects.requireNonNull(variant, "variant");
        Instant now = clock.instant();
        return storage.updateAccount(accountId, current -> {
            List<SkinAsset> assets = replaceSkin(current.skinAssets(), skinId, asset -> asset.withVariant(variant, now));
            return copy(current, assets, current.presets(), now);
        });
    }

    public AccountState duplicateSkin(UUID accountId, UUID skinId, String newName) throws IOException {
        Instant now = clock.instant();
        return storage.updateAccount(accountId, current -> {
            SkinAsset original = findSkin(current, skinId);
            SkinAsset duplicate = original.duplicate(UUID.randomUUID(), newName, now);
            return copy(current, appended(current.skinAssets(), duplicate), current.presets(), now);
        });
    }

    public AccountState deleteSkin(UUID accountId, UUID skinId) throws IOException {
        Instant now = clock.instant();
        return storage.updateAccount(accountId, current -> {
            findSkin(current, skinId);
            boolean inUse = current.presets().stream()
                            .anyMatch(preset -> preset.skin()
                                    .optionalAssetId()
                                    .filter(skinId::equals)
                                    .isPresent())
                    || current.personalSkins().stream()
                            .anyMatch(entry -> entry.variantAssetIds().containsValue(skinId));
            if (inUse) {
                throw new LibraryOperationException(
                        LibraryOperationException.Code.SKIN_IN_USE,
                        "Skin is referenced by a preset or personal catalog entry.");
            }
            List<SkinAsset> assets = current.skinAssets().stream()
                    .filter(asset -> !asset.id().equals(skinId))
                    .toList();
            return copy(current, assets, current.presets(), now);
        });
    }

    public AccountState createPreset(
            UUID accountId,
            String name,
            SkinReference skin,
            String capeId) throws IOException {
        return createPreset(accountId, name, skin, OuterLayerVisibility.allVisible(), capeId);
    }

    public AccountState createPreset(
            UUID accountId,
            String name,
            SkinReference skin,
            OuterLayerVisibility outerLayerVisibility,
            String capeId) throws IOException {
        Objects.requireNonNull(skin, "skin");
        Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
        Instant now = clock.instant();
        return storage.updateAccount(accountId, current -> {
            validateSkinReference(current, skin);
            AppearancePreset preset = new AppearancePreset(
                    UUID.randomUUID(), name, skin, capeId, outerLayerVisibility, now, now);
            return copy(current, current.skinAssets(), appended(current.presets(), preset), now);
        });
    }


    public InitialPresetCreation createInitialPresetIfEmpty(
            UUID accountId,
            String name,
            SkinReference skin,
            String capeId,
            Instant expectedRevision) throws IOException {
        Objects.requireNonNull(skin, "skin");
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        UUID presetId = UUID.randomUUID();
        Instant now = clock.instant();
        boolean[] revisionMatched = {false};
        boolean[] created = {false};
        AccountState state = storage.updateAccount(accountId, current -> {
            if (!current.updatedAt().equals(expectedRevision)) {
                return current;
            }
            revisionMatched[0] = true;
            if (!current.presets().isEmpty()) {
                return current;
            }
            validateSkinReference(current, skin);
            AppearancePreset preset = new AppearancePreset(
                    presetId, name, skin, capeId, now, now);
            created[0] = true;
            return copy(current, current.skinAssets(), List.of(preset), now);
        });
        return new InitialPresetCreation(state, revisionMatched[0], created[0]);
    }


    public AccountState updatePreset(
            UUID accountId,
            UUID presetId,
            String name,
            SkinReference skin,
            String capeId) throws IOException {
        return updatePreset(accountId, presetId, name, skin, OuterLayerVisibility.allVisible(), capeId);
    }

    public AccountState updatePreset(
            UUID accountId,
            UUID presetId,
            String name,
            SkinReference skin,
            OuterLayerVisibility outerLayerVisibility,
            String capeId) throws IOException {
        Objects.requireNonNull(skin, "skin");
        Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
        Instant now = clock.instant();
        return storage.updateAccount(accountId, current -> {
            validateSkinReference(current, skin);
            List<AppearancePreset> presets = new ArrayList<>(current.presets());
            int index = indexOfPreset(presets, presetId);
            AppearancePreset existing = presets.get(index);
            presets.set(index, new AppearancePreset(
                    existing.id(), name, skin, capeId, outerLayerVisibility, existing.createdAt(), now));
            return copy(current, current.skinAssets(), presets, now);
        });
    }


    public PresetDeletion deletePreset(
            UUID accountId,
            UUID presetId,
            NclSkinsStorage.AppearanceIntentFromAccount accountDefaultIntent) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(presetId, "presetId");
        Objects.requireNonNull(accountDefaultIntent, "accountDefaultIntent");
        Instant now = clock.instant();
        boolean[] resetAppearance = {false};
        NclSkinsStorage.AccountAppearanceMutationResult result =
                storage.mutateAccountAndAppearance(accountId, (current, appearance, nextRevision) -> {
                    List<AppearancePreset> presets = new ArrayList<>(current.presets());
                    int index = indexOfPreset(presets, presetId);
                    resetAppearance[0] = presets.size() == 1
                            || presetId.equals(appearance.activePresetId());
                    presets.remove(index);
                    AccountState deleted = copy(current, current.skinAssets(), presets, now);
                    if (!resetAppearance[0]) {
                        return NclSkinsStorage.AccountAppearanceMutationPlan.accountOnly(
                                deleted, appearance);
                    }
                    AccountAppearanceState accountDefault = Objects.requireNonNull(
                            accountDefaultIntent.apply(current, appearance, nextRevision),
                            "account default intent");
                    return NclSkinsStorage.AccountAppearanceMutationPlan.both(
                            deleted, accountDefault);
                });
        return new PresetDeletion(
                result.account(),
                result.appearance(),
                resetAppearance[0],
                result.appearanceUpdated());
    }


    public AccountState resetLibrary(UUID accountId) throws IOException {
        Instant now = clock.instant();
        return storage.updateAccount(accountId, current ->
                AccountState.empty(accountId, nextRevision(current, now)));
    }

    public ResolvedSkinAsset resolveSkin(AccountState state, UUID skinId)
            throws IOException, PngValidationException {
        SkinAsset asset = findSkin(state, skinId);
        byte[] png = storage.readAsset(asset.sha256());
        return new ResolvedSkinAsset(asset.id(), asset.sha256(), asset.variant(), png);
    }

    public AppearancePreset findPreset(AccountState state, UUID presetId) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(presetId, "presetId");
        return state.presets().stream()
                .filter(preset -> preset.id().equals(presetId))
                .findFirst()
                .orElseThrow(() -> new LibraryOperationException(
                        LibraryOperationException.Code.PRESET_NOT_FOUND,
                        "Preset was not found."));
    }

    public SkinAsset findSkin(AccountState state, UUID skinId) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(skinId, "skinId");
        return state.skinAssets().stream()
                .filter(asset -> asset.id().equals(skinId))
                .findFirst()
                .orElseThrow(() -> new LibraryOperationException(
                        LibraryOperationException.Code.SKIN_NOT_FOUND,
                        "Skin was not found."));
    }

    private static void validateSkinReference(AccountState state, SkinReference skin) {
        skin.optionalAssetId().ifPresent(assetId -> {
            boolean exists = state.skinAssets().stream().anyMatch(asset -> asset.id().equals(assetId));
            if (!exists) {
                throw new LibraryOperationException(
                        LibraryOperationException.Code.PRESET_REFERENCES_MISSING_SKIN,
                        "Preset references a missing skin.");
            }
        });
    }

    private static List<SkinAsset> replaceSkin(
            List<SkinAsset> assets,
            UUID skinId,
            java.util.function.UnaryOperator<SkinAsset> replace) {
        List<SkinAsset> result = new ArrayList<>(assets);
        int index = -1;
        for (int candidate = 0; candidate < result.size(); candidate++) {
            if (result.get(candidate).id().equals(skinId)) {
                index = candidate;
                break;
            }
        }
        if (index < 0) {
            throw new LibraryOperationException(
                    LibraryOperationException.Code.SKIN_NOT_FOUND,
                    "Skin was not found.");
        }
        result.set(index, replace.apply(result.get(index)));
        return List.copyOf(result);
    }

    private static Optional<SkinAsset> reusablePersonalAsset(
            AccountState state,
            PersonalSkinEntry entry,
            String sha256,
            SkinVariant variant) {
        if (entry != null) {
            Optional<UUID> mapped = entry.optionalAssetId(variant);
            if (mapped.isPresent()) {
                UUID assetId = mapped.orElseThrow();
                return state.skinAssets().stream()
                        .filter(asset -> asset.id().equals(assetId))
                        .findFirst();
            }
        }
        return state.skinAssets().stream()
                .filter(asset -> asset.sha256().equals(sha256))
                .filter(asset -> asset.variant() == variant)
                .filter(asset -> asset.catalogOrigin().isEmpty())
                .filter(asset -> asset.source() == SkinSource.IMPORTED
                        || asset.source() == SkinSource.DUPLICATED)
                .min(Comparator.comparing(SkinAsset::createdAt)
                        .thenComparing(asset -> asset.id().toString()));
    }

    private static Optional<SkinAsset> reusablePersonalAsset(
            List<SkinAsset> assets,
            PersonalSkinEntry entry,
            String sha256,
            SkinVariant variant) {
        if (entry != null) {
            Optional<UUID> mapped = entry.optionalAssetId(variant);
            if (mapped.isPresent()) {
                UUID assetId = mapped.orElseThrow();
                Optional<SkinAsset> existing = assets.stream()
                        .filter(asset -> asset.id().equals(assetId))
                        .findFirst();
                if (existing.isPresent()) {
                    return existing;
                }
            }
        }
        return assets.stream()
                .filter(asset -> asset.sha256().equals(sha256))
                .filter(asset -> asset.variant() == variant)
                .filter(asset -> asset.catalogOrigin().isEmpty())
                .filter(asset -> asset.source() == SkinSource.IMPORTED
                        || asset.source() == SkinSource.DUPLICATED)
                .min(Comparator.comparing(SkinAsset::createdAt)
                        .thenComparing(asset -> asset.id().toString()));
    }

    private static void replacePersonalSkinInPlace(
            List<PersonalSkinEntry> personalSkins,
            PersonalSkinEntry existing,
            PersonalSkinEntry replacement) {
        if (existing == null) {
            personalSkins.add(replacement);
            return;
        }
        for (int index = 0; index < personalSkins.size(); index++) {
            if (personalSkins.get(index).sha256().equals(existing.sha256())) {
                personalSkins.set(index, replacement);
                return;
            }
        }
        throw new IllegalStateException("Personal skin disappeared during batch import");
    }

    private static boolean containsEquivalentPreset(
            List<AppearancePreset> presets,
            List<SkinAsset> assets,
            String name,
            String sha256,
            SkinVariant variant,
            String capeId) {
        return presets.stream()
                .filter(preset -> preset.name().equals(name))
                .filter(preset -> Objects.equals(preset.capeId(), normalizeCapeId(capeId)))
                .filter(preset -> preset.outerLayerVisibility().equals(OuterLayerVisibility.allVisible()))
                .map(AppearancePreset::skin)
                .map(SkinReference::optionalAssetId)
                .flatMap(Optional::stream)
                .map(assetId -> assets.stream()
                        .filter(asset -> asset.id().equals(assetId))
                        .findFirst())
                .flatMap(Optional::stream)
                .anyMatch(asset -> asset.sha256().equals(sha256) && asset.variant() == variant);
    }

    private static String normalizeCapeId(String capeId) {
        if (capeId == null) {
            return null;
        }
        String trimmed = capeId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<PersonalSkinEntry> replacePersonalSkin(
            List<PersonalSkinEntry> personalSkins,
            PersonalSkinEntry existing,
            PersonalSkinEntry replacement) {
        List<PersonalSkinEntry> result = new ArrayList<>(personalSkins);
        if (existing == null) {
            result.add(replacement);
            return List.copyOf(result);
        }
        for (int index = 0; index < result.size(); index++) {
            if (result.get(index).sha256().equals(existing.sha256())) {
                result.set(index, replacement);
                return List.copyOf(result);
            }
        }
        throw new IllegalStateException("Personal skin disappeared during account update");
    }

    private static String requirePersonalSkinDisplayName(String value) {
        Objects.requireNonNull(value, "personalSkinDisplayName");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 128) {
            throw new IllegalArgumentException(
                    "personalSkinDisplayName must contain between 1 and 128 characters");
        }
        return trimmed;
    }

    private StoredAsset storeOrReusePersonalAsset(
            UUID accountId, byte[] pngBytes, String incomingRenderSha256)
            throws IOException, PngValidationException {
        AccountState current = storage.loadOrCreateAccount(accountId);
        Optional<StoredAsset> reusable = findReusablePersonalAsset(
                current, incomingRenderSha256);
        return reusable.isPresent() ? reusable.orElseThrow() : storage.storeAsset(pngBytes);
    }

    private StoredAsset recheckPersonalAssetReuse(
            AccountState current,
            String incomingRenderSha256,
            StoredAsset fallback) {
        return findReusablePersonalAsset(current, incomingRenderSha256).orElse(fallback);
    }

    private Optional<StoredAsset> findReusablePersonalAsset(
            AccountState current, String incomingRenderSha256) {
        for (PersonalSkinEntry entry : current.personalSkins()) {
            try {
                byte[] existingPng = storage.readAsset(entry.sha256());
                if (incomingRenderSha256.equals(pngValidator.renderSha256(existingPng))) {
                    return Optional.of(new StoredAsset(
                            entry.sha256(),
                            storage.assetPath(entry.sha256()),
                            pngValidator.validate(existingPng),
                            true));
                }
            } catch (IOException | PngValidationException invalidExistingAsset) {
            }
        }
        return Optional.empty();
    }

    private static String requireSha256(String value) {
        Objects.requireNonNull(value, "sha256");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "sha256 must contain 64 lowercase hexadecimal characters");
        }
        return value;
    }

    public record PresetDeletion(
            AccountState state,
            AccountAppearanceState appearance,
            boolean resetsAppearance,
            boolean appearanceUpdated) {
        public PresetDeletion {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(appearance, "appearance");
            if (!state.accountId().equals(appearance.accountId())) {
                throw new IllegalArgumentException("Account and appearance UUIDs differ");
            }
        }
    }

    public record PersonalSkinPresetImport(
            String presetName,
            String personalSkinDisplayName,
            SkinVariant variant,
            PersonalSkinSource source,
            byte[] pngBytes,
            String capeId) {
        public PersonalSkinPresetImport {
            presetName = requirePersonalSkinDisplayName(presetName);
            personalSkinDisplayName = requirePersonalSkinDisplayName(personalSkinDisplayName);
            Objects.requireNonNull(variant, "variant");
            Objects.requireNonNull(source, "source");
            pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
            if (pngBytes.length == 0) {
                throw new IllegalArgumentException("pngBytes must not be empty");
            }
            capeId = normalizeCapeId(capeId);
        }

        @Override
        public byte[] pngBytes() {
            return pngBytes.clone();
        }
    }

    public record BatchPersonalSkinPresetImport(
            AccountState state, int imported, int alreadyPresent) {
        public BatchPersonalSkinPresetImport {
            Objects.requireNonNull(state, "state");
            if (imported < 0 || alreadyPresent < 0) {
                throw new IllegalArgumentException("import counters must not be negative");
            }
        }
    }

    private record PreparedPersonalSkinPresetImport(
            PersonalSkinPresetImport candidate,
            StoredAsset stored,
            String renderSha256) {
        private PreparedPersonalSkinPresetImport {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(stored, "stored");
            Objects.requireNonNull(renderSha256, "renderSha256");
        }
    }

    public record InitialPresetCreation(
            AccountState state, boolean revisionMatched, boolean created) {
        public InitialPresetCreation {
            Objects.requireNonNull(state, "state");
            if (created && !revisionMatched) {
                throw new IllegalArgumentException(
                        "an initial preset cannot be created after a revision mismatch");
            }
        }
    }

    private static int indexOfPreset(List<AppearancePreset> presets, UUID presetId) {
        for (int index = 0; index < presets.size(); index++) {
            if (presets.get(index).id().equals(presetId)) {
                return index;
            }
        }
        throw new LibraryOperationException(
                LibraryOperationException.Code.PRESET_NOT_FOUND,
                "Preset was not found.");
    }

    private static AccountState copy(
            AccountState current,
            List<SkinAsset> assets,
            List<AppearancePreset> presets,
            Instant now) {
        return new AccountState(
                AccountState.CURRENT_SCHEMA_VERSION,
                current.accountId(),
                assets,
                current.personalSkins(),
                presets,
                nextRevision(current, now));
    }


    private static Instant nextRevision(AccountState current, Instant now) {
        return now.isAfter(current.updatedAt()) ? now : current.updatedAt().plusNanos(1L);
    }

    private static <T> List<T> appended(List<T> values, T value) {
        List<T> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }
}
