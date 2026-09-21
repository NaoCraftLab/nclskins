package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.EditorTab;
import com.naocraftlab.skins.core.model.OwnedCapeEntry;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


public final class PresetEditorModel {
    private static final int CONTENT_TOP_INSET = 7;
    private static final int CAPE_GALLERY_TOP = 33 + CONTENT_TOP_INSET;
    private static final int FOOTER_HEIGHT = 33;
    private static final int MODEL_CARD_ASPECT_WIDTH = 68;
    private static final int MODEL_CARD_ASPECT_HEIGHT = 86;
    private static final int MODEL_GALLERY_TOP = 95;
    private static final int CAPE_SCROLLBAR_WIDTH = 6;
    private final Optional<UUID> originalPresetId;
    private final String name;
    private final SkinReference skin;
    private final SkinVariant initialVariant;
    private final SkinVariant variant;
    private final Optional<String> capeId;
    private final List<CapeChoice> capeChoices;
    private final boolean hasOwnedCapePreview;
    private final Optional<DraftPng> png;
    private final Optional<CatalogOrigin> catalogOrigin;
    private final Map<SkinVariant, DraftPng> catalogVariants;
    private final Map<SkinVariant, SkinReference> reusableCatalogVariants;
    private final boolean busy;
    private final Optional<UiMessage> status;
    private final PreviewInteractionModel preview;
    private final EditorTab selectedEditorTab;
    private final CapeCatalogModel capeCatalog;

    private PresetEditorModel(
            Optional<UUID> originalPresetId,
            String name,
            SkinReference skin,
            SkinVariant initialVariant,
            SkinVariant variant,
            Optional<String> capeId,
            List<CapeChoice> capeChoices,
            boolean hasOwnedCapePreview,
            Optional<DraftPng> png,
            Optional<CatalogOrigin> catalogOrigin,
            Map<SkinVariant, DraftPng> catalogVariants,
            Map<SkinVariant, SkinReference> reusableCatalogVariants,
            boolean busy,
            Optional<UiMessage> status,
            PreviewInteractionModel preview) {
        this(originalPresetId, name, skin, initialVariant, variant, capeId, capeChoices, hasOwnedCapePreview, png, catalogOrigin, catalogVariants, reusableCatalogVariants, busy, status, preview, EditorTab.APPEARANCE);
    }

    private PresetEditorModel(
            Optional<UUID> originalPresetId,
            String name,
            SkinReference skin,
            SkinVariant initialVariant,
            SkinVariant variant,
            Optional<String> capeId,
            List<CapeChoice> capeChoices,
            boolean hasOwnedCapePreview,
            Optional<DraftPng> png,
            Optional<CatalogOrigin> catalogOrigin,
            Map<SkinVariant, DraftPng> catalogVariants,
            Map<SkinVariant, SkinReference> reusableCatalogVariants,
            boolean busy,
            Optional<UiMessage> status,
            PreviewInteractionModel preview,
            EditorTab selectedEditorTab) {
        this(originalPresetId, name, skin, initialVariant, variant, capeId, capeChoices, hasOwnedCapePreview,
                png, catalogOrigin, catalogVariants, reusableCatalogVariants, busy, status, preview, selectedEditorTab, null);
    }

    private PresetEditorModel(
            Optional<UUID> originalPresetId,
            String name,
            SkinReference skin,
            SkinVariant initialVariant,
            SkinVariant variant,
            Optional<String> capeId,
            List<CapeChoice> capeChoices,
            boolean hasOwnedCapePreview,
            Optional<DraftPng> png,
            Optional<CatalogOrigin> catalogOrigin,
            Map<SkinVariant, DraftPng> catalogVariants,
            Map<SkinVariant, SkinReference> reusableCatalogVariants,
            boolean busy,
            Optional<UiMessage> status,
            PreviewInteractionModel preview,
            EditorTab selectedEditorTab, CapeCatalogModel capeCatalog) {
        this.capeCatalog = capeCatalog;
        this.originalPresetId = Objects.requireNonNull(originalPresetId, "originalPresetId");
        this.name = Objects.requireNonNull(name, "name");
        this.skin = Objects.requireNonNull(skin, "skin");
        this.initialVariant = Objects.requireNonNull(initialVariant, "initialVariant");
        this.variant = Objects.requireNonNull(variant, "variant");
        this.capeId = Objects.requireNonNull(capeId, "capeId");
        this.capeChoices = List.copyOf(Objects.requireNonNull(capeChoices, "capeChoices"));
        this.hasOwnedCapePreview = hasOwnedCapePreview;
        this.png = Objects.requireNonNull(png, "png");
        this.catalogOrigin = Objects.requireNonNull(catalogOrigin, "catalogOrigin");
        this.catalogVariants = Map.copyOf(Objects.requireNonNull(catalogVariants, "catalogVariants"));
        this.reusableCatalogVariants = Map.copyOf(
                Objects.requireNonNull(reusableCatalogVariants, "reusableCatalogVariants"));
        if (catalogOrigin.isPresent() && !this.reusableCatalogVariants.isEmpty()) {
            throw new IllegalArgumentException("external and reusable catalog origins are exclusive");
        }
        if (!catalogVariants.isEmpty()
                && (png.isEmpty() || !catalogVariants.containsKey(variant))) {
            throw new IllegalArgumentException("catalog editor requires the selected catalog PNG");
        }
        if (!this.reusableCatalogVariants.isEmpty()
                && (!this.reusableCatalogVariants.keySet().equals(catalogVariants.keySet())
                        || !this.reusableCatalogVariants.get(variant).equals(skin))) {
            throw new IllegalArgumentException("reusable catalog variants must match the selected skin");
        }
        this.busy = busy;
        this.status = Objects.requireNonNull(status, "status");
        this.preview = Objects.requireNonNull(preview, "preview");
        this.selectedEditorTab = Objects.requireNonNull(selectedEditorTab, "selectedEditorTab");
    }

    public static PresetEditorModel open(
            AccountState state,
            Optional<AppearancePreset> original,
            Optional<RemoteProfile> profile,
            Optional<UUID> activePresetId,
            TextResolver textResolver,
            int viewportHeight,
            PreviewRenderer.CapeMode preferredCapeMode) {
        return open(
                state,
                original,
                profile,
                activePresetId,
                textResolver,
                viewportHeight,
                preferredCapeMode,
                SkinVariant.CLASSIC);
    }

    public static PresetEditorModel open(
            AccountState state,
            Optional<AppearancePreset> original,
            Optional<RemoteProfile> profile,
            Optional<UUID> activePresetId,
            TextResolver textResolver,
            int viewportHeight,
            PreviewRenderer.CapeMode preferredCapeMode,
            SkinVariant accountDefaultVariant) {
        return open(state, original, profile, activePresetId, textResolver, viewportHeight,
                preferredCapeMode, accountDefaultVariant, List.of());
    }

    public static PresetEditorModel open(
            AccountState state,
            Optional<AppearancePreset> original,
            Optional<RemoteProfile> profile,
            Optional<UUID> activePresetId,
            TextResolver textResolver,
            int viewportHeight,
            PreviewRenderer.CapeMode preferredCapeMode,
            SkinVariant accountDefaultVariant,
            List<OwnedCapeEntry> cachedCapes) {
        Objects.requireNonNull(state, "state");
        original = Objects.requireNonNull(original, "original");
        profile = Objects.requireNonNull(profile, "profile");
        activePresetId = Objects.requireNonNull(activePresetId, "activePresetId");
        Objects.requireNonNull(textResolver, "textResolver");
        Objects.requireNonNull(preferredCapeMode, "preferredCapeMode");
        Objects.requireNonNull(accountDefaultVariant, "accountDefaultVariant");

        SkinReference initialSkin;
        SkinVariant initialVariant;
        if (original.isPresent()) {
            initialSkin = original.orElseThrow().skin();
            initialVariant = variantFor(state, initialSkin, accountDefaultVariant);
        } else {
            SkinAsset defaultAsset = state.skinAssets().stream()
                    .filter(asset -> asset.source() == SkinSource.VANILLA_DEFAULT)
                    .filter(asset -> asset.variant() == accountDefaultVariant)
                    .findFirst()
                    .or(() -> state.skinAssets().stream()
                            .filter(asset -> asset.source() == SkinSource.VANILLA_DEFAULT)
                            .findFirst())
                    .orElseThrow(() -> new IllegalStateException("Bundled skin is unavailable"));
            initialSkin = SkinReference.asset(defaultAsset.id());
            initialVariant = defaultAsset.variant();
        }
        String initialName = original.map(AppearancePreset::name).orElseGet(() -> textResolver.resolve(
                UiMessage.info("nclskins.editor.default_name", state.presets().size() + 1)));
        Optional<String> initialCape = original.flatMap(AppearancePreset::optionalCapeId);
        List<CapeChoice> choices = capeChoices(profile, cachedCapes, initialCape);
        return new PresetEditorModel(
                original.map(AppearancePreset::id),
                initialName,
                initialSkin,
                initialVariant,
                initialVariant,
                initialCape,
                choices,
                hasOwnedCapePreview(profile, cachedCapes),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                false,
                Optional.empty(),
                PreviewInteractionModel.editor(viewportHeight, preferredCapeMode)
                        .withOuterLayerVisibility(original
                                .map(AppearancePreset::outerLayerVisibility)
                                .orElseGet(OuterLayerVisibility::allVisible)));
    }

    public static PresetEditorModel openDuplicate(
            AccountState state,
            AppearancePreset source,
            String duplicateName,
            Optional<RemoteProfile> profile,
            Optional<UUID> activePresetId,
            TextResolver textResolver,
            int viewportHeight,
            PreviewRenderer.CapeMode preferredCapeMode,
            SkinVariant accountDefaultVariant,
            List<OwnedCapeEntry> cachedCapes) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(duplicateName, "duplicateName");
        PresetEditorModel sourceSnapshot = open(
                state,
                Optional.of(source),
                profile,
                activePresetId,
                textResolver,
                viewportHeight,
                preferredCapeMode,
                accountDefaultVariant,
                cachedCapes);
        return new PresetEditorModel(
                Optional.empty(),
                duplicateName,
                sourceSnapshot.skin,
                sourceSnapshot.initialVariant,
                sourceSnapshot.variant,
                sourceSnapshot.capeId,
                sourceSnapshot.capeChoices,
                sourceSnapshot.hasOwnedCapePreview,
                sourceSnapshot.png,
                sourceSnapshot.catalogOrigin,
                sourceSnapshot.catalogVariants,
                sourceSnapshot.reusableCatalogVariants,
                false,
                Optional.empty(),
                sourceSnapshot.preview);
    }

    public static PresetEditorModel openCatalog(
            String name,
            CatalogOrigin origin,
            Map<SkinVariant, byte[]> normalizedVariants,
            SkinVariant initialVariant,
            Optional<RemoteProfile> profile,
            int viewportHeight,
            PreviewRenderer.CapeMode preferredCapeMode) {
        return openCatalog(name, origin, normalizedVariants, initialVariant, profile,
                List.of(), viewportHeight, preferredCapeMode);
    }

    public static PresetEditorModel openCatalog(
            String name,
            CatalogOrigin origin,
            Map<SkinVariant, byte[]> normalizedVariants,
            SkinVariant initialVariant,
            Optional<RemoteProfile> profile,
            List<OwnedCapeEntry> cachedCapes,
            int viewportHeight,
            PreviewRenderer.CapeMode preferredCapeMode) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(normalizedVariants, "normalizedVariants");
        Objects.requireNonNull(initialVariant, "initialVariant");
        profile = Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(preferredCapeMode, "preferredCapeMode");
        java.util.EnumMap<SkinVariant, DraftPng> drafts = new java.util.EnumMap<>(SkinVariant.class);
        normalizedVariants.forEach((variant, bytes) -> drafts.put(
                Objects.requireNonNull(variant, "catalog variant"),
                new DraftPng(name + "-" + variant.name().toLowerCase(java.util.Locale.ROOT) + ".png", bytes)));
        DraftPng selected = drafts.get(initialVariant);
        if (selected == null) {
            throw new IllegalArgumentException("initial catalog variant is unavailable");
        }
        return new PresetEditorModel(
                Optional.empty(),
                name,
                SkinReference.accountDefault(),
                initialVariant,
                initialVariant,
                Optional.empty(),
                capeChoices(profile, cachedCapes, Optional.empty()),
                hasOwnedCapePreview(profile, cachedCapes),
                Optional.of(selected),
                Optional.of(origin),
                drafts,
                Map.of(),
                false,
                Optional.empty(),
                PreviewInteractionModel.editor(viewportHeight, preferredCapeMode));
    }

    public static PresetEditorModel openPersonalCatalog(
            String name,
            Map<SkinVariant, ReusableCatalogVariant> variants,
            SkinVariant initialVariant,
            Optional<RemoteProfile> profile,
            int viewportHeight,
            PreviewRenderer.CapeMode preferredCapeMode) {
        return openPersonalCatalog(name, variants, initialVariant, profile,
                List.of(), viewportHeight, preferredCapeMode);
    }

    public static PresetEditorModel openPersonalCatalog(
            String name,
            Map<SkinVariant, ReusableCatalogVariant> variants,
            SkinVariant initialVariant,
            Optional<RemoteProfile> profile,
            List<OwnedCapeEntry> cachedCapes,
            int viewportHeight,
            PreviewRenderer.CapeMode preferredCapeMode) {
        Objects.requireNonNull(name, "name");
        variants = Map.copyOf(Objects.requireNonNull(variants, "variants"));
        Objects.requireNonNull(initialVariant, "initialVariant");
        profile = Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(preferredCapeMode, "preferredCapeMode");
        java.util.EnumMap<SkinVariant, DraftPng> drafts = new java.util.EnumMap<>(SkinVariant.class);
        java.util.EnumMap<SkinVariant, SkinReference> references =
                new java.util.EnumMap<>(SkinVariant.class);
        variants.forEach((variant, selection) -> {
            Objects.requireNonNull(variant, "catalog variant");
            Objects.requireNonNull(selection, "catalog selection");
            drafts.put(
                    variant,
                    new DraftPng(
                            name + "-" + variant.name().toLowerCase(java.util.Locale.ROOT) + ".png",
                            selection.pngBytes()));
            references.put(variant, selection.skin());
        });
        DraftPng selected = drafts.get(initialVariant);
        SkinReference selectedSkin = references.get(initialVariant);
        if (selected == null || selectedSkin == null) {
            throw new IllegalArgumentException("initial personal catalog variant is unavailable");
        }
        return new PresetEditorModel(
                Optional.empty(),
                name,
                selectedSkin,
                initialVariant,
                initialVariant,
                Optional.empty(),
                capeChoices(profile, cachedCapes, Optional.empty()),
                hasOwnedCapePreview(profile, cachedCapes),
                Optional.of(selected),
                Optional.empty(),
                drafts,
                references,
                false,
                Optional.empty(),
                PreviewInteractionModel.editor(viewportHeight, preferredCapeMode));
    }

    public Optional<UUID> originalPresetId() {
        return originalPresetId;
    }

    public String name() {
        return name;
    }

    public SkinReference skin() {
        return skin;
    }

    public SkinVariant variant() {
        return variant;
    }

    public CapeCatalogModel capeCatalog() { return capeCatalog; }

    public PresetEditorModel withCapeCatalog(CapeCatalogModel value) {
        return new PresetEditorModel(originalPresetId, name, skin, initialVariant, variant,
                value == null ? capeId : value.minecraft(), capeChoices, hasOwnedCapePreview, png, catalogOrigin,
                catalogVariants, reusableCatalogVariants, busy, status, preview, selectedEditorTab, value);
    }

    private Optional<String> previewCapeId() {
        return capeCatalog == null ? capeId : capeCatalog.previewCape();
    }

    public Optional<String> capeId() {
        return capeId;
    }

    public List<CapeChoice> capeChoices() {
        return capeChoices;
    }

    public Optional<DraftPng> png() {
        return png;
    }

    public Optional<CatalogOrigin> catalogOrigin() {
        return catalogOrigin;
    }

    public java.util.Set<SkinVariant> availableCatalogVariants() {
        return catalogVariants.keySet();
    }

    public boolean busy() {
        return busy;
    }

    public Optional<UiMessage> status() {
        return status;
    }

    public PreviewInteractionModel preview() {
        return preview;
    }

    public PresetEditorModel withName(String value) {
        Objects.requireNonNull(value, "value");
        return copy(value, skin, variant, capeId, png, busy, status, preview);
    }

    public PresetEditorModel toggleVariant() {
        return selectVariant(variant == SkinVariant.CLASSIC ? SkinVariant.SLIM : SkinVariant.CLASSIC);
    }

    public PresetEditorModel selectVariant(SkinVariant next) {
        Objects.requireNonNull(next, "next");
        if (busy || next == variant || !variantAvailable(next)) {
            return this;
        }
        Optional<DraftPng> nextPng = png;
        if (!catalogVariants.isEmpty()) {
            nextPng = Optional.ofNullable(catalogVariants.get(next));
            if (nextPng.isEmpty()) {
                return this;
            }
        }
        SkinReference nextSkin = reusableCatalogVariants.getOrDefault(next, skin);
        return copy(name, nextSkin, next, capeId, nextPng, false, status, preview);
    }

    public PresetEditorModel cycleCape(int direction) {
        if (busy || direction == 0 || capeChoices.isEmpty()) {
            return this;
        }
        int current = 0;
        for (int index = 0; index < capeChoices.size(); index++) {
            if (capeChoices.get(index).id().equals(capeId)) {
                current = index;
                break;
            }
        }
        Optional<String> next = capeChoices.get(Math.floorMod(current + direction, capeChoices.size())).id();
        return copy(name, skin, variant, next, png, false, status, preview);
    }

    public PresetEditorModel selectCape(int index) {
        if (busy || index < 0 || index >= capeChoices.size()) {
            return this;
        }
        return copy(name, skin, variant, capeChoices.get(index).id(), png, false, status, preview);
    }

    public boolean variantAvailable(SkinVariant value) {
        return catalogVariants.isEmpty() || catalogVariants.containsKey(value);
    }

    public boolean modelVariantSelectable() {
        return catalogVariants.isEmpty() || catalogVariants.size() > 1;
    }

    public PresetEditorModel cyclePreviewMode() {
        return cyclePreviewMode(1);
    }

    public PresetEditorModel cyclePreviewMode(int direction) {
        if (busy || direction == 0) {
            return this;
        }
        return copy(
                name,
                skin,
                variant,
                capeId,
                png,
                false,
                status,
                preview.cycleCapeMode(hasCapePreview()));
    }

    public PresetEditorModel cycleOuterLayer(String control, int direction) {
        if (busy || direction == 0) {
            return this;
        }
        OuterLayerVisibility visibility = EditorOuterLayerCycle.cycle(
                control, preview.outerLayerVisibility(), direction);
        return copy(
                name,
                skin,
                variant,
                capeId,
                png,
                false,
                status,
                preview.withOuterLayerVisibility(visibility));
    }

    public PresetEditorModel toggleOuterLayer() {
        if (busy) {
            return this;
        }
        return copy(name, skin, variant, capeId, png, false, status, preview.toggleOuterLayer());
    }

    public PresetEditorModel toggleOuterLayerPart(OuterLayerPart part) {
        if (busy) {
            return this;
        }
        return copy(name, skin, variant, capeId, png, false, status, preview.toggleOuterLayerPart(part));
    }

    public PresetEditorModel toggleOuterLayerGroup(String group) {
        if (busy) {
            return this;
        }
        List<OuterLayerPart> parts = switch (Objects.requireNonNull(group, "group")) {
            case "body_arms" -> List.of(
                    OuterLayerPart.BODY, OuterLayerPart.LEFT_ARM, OuterLayerPart.RIGHT_ARM);
            case "legs" -> List.of(OuterLayerPart.LEFT_LEG, OuterLayerPart.RIGHT_LEG);
            case "all" -> List.of(OuterLayerPart.values());
            default -> throw new IllegalArgumentException("unknown outer-layer group: " + group);
        };
        return copy(name, skin, variant, capeId, png, false, status, preview.toggleOuterLayerGroup(parts));
    }

    public PresetEditorModel withPreview(PreviewInteractionModel value) {
        return copy(name, skin, variant, capeId, png, busy, status, Objects.requireNonNull(value, "value"));
    }

    public PresetEditorModel withPng(String fileName, byte[] normalizedBytes) {
        return withPng(fileName, normalizedBytes, initialVariant, variant);
    }

    public PresetEditorModel withImportedPng(
            String fileName, byte[] normalizedBytes, SkinVariant importedVariant) {
        Objects.requireNonNull(importedVariant, "importedVariant");
        return withPng(fileName, normalizedBytes, importedVariant, importedVariant);
    }

    private PresetEditorModel withPng(
            String fileName,
            byte[] normalizedBytes,
            SkinVariant nextInitialVariant,
            SkinVariant nextVariant) {
        if (busy) {
            return this;
        }
        Objects.requireNonNull(nextInitialVariant, "nextInitialVariant");
        Objects.requireNonNull(nextVariant, "nextVariant");
        DraftPng draft = new DraftPng(fileName, normalizedBytes);
        return new PresetEditorModel(
                originalPresetId,
                name,
                skin,
                nextInitialVariant,
                nextVariant,
                capeId,
                capeChoices,
                hasOwnedCapePreview,
                Optional.of(draft),
                Optional.empty(),
                Map.of(),
                Map.of(),
                false,
                Optional.empty(),
                preview, selectedEditorTab, capeCatalog);
    }

    public PresetEditorModel withBusy(UiMessage message) {
        return copy(
                name,
                skin,
                variant,
                capeId,
                png,
                true,
                Optional.of(Objects.requireNonNull(message, "message")),
                preview.endRotate());
    }

    public PresetEditorModel withBusyWithoutStatus() {
        return copy(name, skin, variant, capeId, png, true, Optional.empty(), preview.endRotate());
    }

    public PresetEditorModel withoutStatus() {
        return copy(name, skin, variant, capeId, png, false, Optional.empty(), preview.endRotate());
    }

    public PresetEditorModel withStatus(UiMessage message) {
        return copy(
                name,
                skin,
                variant,
                capeId,
                png,
                false,
                Optional.of(Objects.requireNonNull(message, "message")),
                preview.endRotate());
    }


    public PresetEditorModel withPreviewFailure(UiMessage message) {
        return copy(
                name,
                skin,
                variant,
                capeId,
                png,
                busy,
                Optional.of(Objects.requireNonNull(message, "message")),
                preview);
    }

    public PresetEditorModel withoutPreviewFailure(UiMessage message) {
        Objects.requireNonNull(message, "message");
        if (!status.equals(Optional.of(message))) {
            return this;
        }
        return copy(name, skin, variant, capeId, png, busy, Optional.empty(), preview);
    }

    public ClientOperations.EditorSaveRequest saveRequest() {
        boolean reuseCatalogAsset = !reusableCatalogVariants.isEmpty();
        Optional<byte[]> bytesToPersist = reuseCatalogAsset
                ? Optional.empty()
                : png.map(DraftPng::bytes);
        Optional<String> personalSkinName = bytesToPersist.isPresent() && catalogOrigin.isEmpty()
                ? png.map(DraftPng::sourceName)
                : Optional.empty();
        return new ClientOperations.EditorSaveRequest(
                originalPresetId,
                name,
                skin,
                initialVariant,
                variant,
                capeId,
                preview.outerLayerVisibility(),
                bytesToPersist,
                catalogOrigin,
                personalSkinName).withOfflineCape(capeCatalog == null ? null : capeCatalog.offline());
    }

    public EditorTab selectedEditorTab() {
        return selectedEditorTab;
    }

    public PresetEditorModel withSelectedEditorTab(EditorTab tab) {
        Objects.requireNonNull(tab, "tab");
        if (busy) {
            return this;
        }
        return new PresetEditorModel(
                originalPresetId, name, skin, initialVariant, variant, capeId, capeChoices,
                hasOwnedCapePreview, png, catalogOrigin, catalogVariants, reusableCatalogVariants,
                busy, status, preview, tab, capeCatalog == null ? null : capeCatalog.resetInspection());
    }

    public ViewSpec present(int width, int height) {
        return present(width, height, initialCapeScrollPosition(width, height), 0.0,
                ViewChromeMetrics.STANDARD);
    }

    public ViewSpec present(int width, int height, double capeScrollPosition) {
        return present(width, height, capeScrollPosition, 0.0, ViewChromeMetrics.STANDARD);
    }

    public ViewSpec present(int width, int height, double capeScrollPosition, double modelScrollPosition) {
        return present(width, height, capeScrollPosition, modelScrollPosition, ViewChromeMetrics.STANDARD);
    }

    public ViewSpec present(
            int width,
            int height,
            double capeScrollPosition,
            double modelScrollPosition,
            ViewChromeMetrics chromeMetrics) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("view dimensions must be positive");
        }
        Objects.requireNonNull(chromeMetrics, "chromeMetrics");
        int dividerX = width / 2;
        int controlsX = Math.min(width - 1, dividerX + CollectionGridLayout.CONTENT_LEFT_INSET);
        EditorCardLayout modelGallery = modelGalleryLayout(width, height, modelScrollPosition);
        int appearanceScroll = (int) Math.round(modelGallery.position());
        int controlsWidth = Math.max(1, width - controlsX - CollectionGridLayout.CONTENT_LEFT_INSET);
        Bounds previewBounds = new Bounds(0, 0, Math.max(1, dividerX), Math.max(1, height));
        int footerWidth = Math.min(260, Math.max(154, width / 2 - 28));
        int footerX = (width - footerWidth) / 2;
        int half = Math.max(54, (footerWidth - 4) / 2);
        int bottom = height - 28;

        List<ViewSpec.Panel> panels = new ArrayList<>();
        panels.add(new ViewSpec.Panel(
                "header", new Bounds(0, 0, width, 33), ViewSpec.Panel.Style.VANILLA_HEADER));
        panels.add(new ViewSpec.Panel(
                "footer", new Bounds(0, Math.max(0, height - FOOTER_HEIGHT), width, FOOTER_HEIGHT), ViewSpec.Panel.Style.VANILLA_FOOTER));
        panels.add(new ViewSpec.Panel(
                "editor.tab_content",
                new Bounds(dividerX, 33, Math.max(1, width - dividerX),
                        Math.max(1, height - 33 - FOOTER_HEIGHT)),
                ViewSpec.Panel.Style.VANILLA_TAB_CONTENT));
        List<ViewSpec.Widget> widgets = new ArrayList<>();
        List<ViewSpec.Text> texts = new ArrayList<>();
        widgets.add(ViewSpec.Widget.verticalTabButton(
                "editor.tab.appearance", VerticalTabStyle.bounds(dividerX, 0),
                UiMessage.info("nclskins.editor.tab.appearance"), GuiIcon.EDITOR_TAB_APPEARANCE,
                selectedEditorTab == EditorTab.APPEARANCE, !busy));
        widgets.add(ViewSpec.Widget.verticalTabButton(
                "editor.tab.cape", VerticalTabStyle.bounds(dividerX, 1),
                UiMessage.info("nclskins.editor.tab.cape"), GuiIcon.EDITOR_TAB_CAPE,
                selectedEditorTab == EditorTab.CAPE, !busy));
        if (selectedEditorTab == EditorTab.APPEARANCE) {
            widgets.add(ViewSpec.Widget.textField(
                    "editor.name",
                    new Bounds(controlsX, 52 - appearanceScroll, controlsWidth, 20),
                    UiMessage.info("nclskins.editor.name"),
                    name,
                    UiMessage.info("nclskins.editor.name_hint"),
                    !busy,
                    128,
                    true,
                    Optional.empty()));
            catalogOrigin.flatMap(PresetEditorModel::catalogInfo).ifPresent(info -> widgets.add(
                    ViewSpec.Widget.infoButton(
                            "editor.catalog_info",
                            new Bounds(controlsX + controlsWidth - 16, 40 - appearanceScroll, 16, 14),
                            UiMessage.literal(info, UiMessage.Severity.INFO),
                            !busy)));
        }
        List<ViewSpec.BackEquipmentPreview> backEquipmentPreviews = new ArrayList<>();
        List<ViewSpec.IconDecoration> iconDecorations = new ArrayList<>();
        List<ViewSpec.ClipRegion> clipRegions = new ArrayList<>();
        List<ViewSpec.TooltipRegion> tooltipRegions = new ArrayList<>();
        List<ViewSpec.NavigationNode> navigationNodes = new ArrayList<>();
        EditorCardLayout capeGallery = capeGalleryLayout(
                dividerX, width - dividerX, height, capeScrollPosition, chromeMetrics);
        if (selectedEditorTab == EditorTab.APPEARANCE) {
            texts.add(new ViewSpec.Text(
                    "editor.model_label", new Bounds(controlsX, 79 - appearanceScroll, controlsWidth, 10),
                    UiMessage.info("nclskins.editor.choose_model"), ViewSpec.Text.Alignment.LEFT));
            addModelGallery(widgets, panels, texts, iconDecorations, clipRegions, navigationNodes, modelGallery);
        }
        if (selectedEditorTab == EditorTab.CAPE) {
            addCapeGallery(
                    widgets,
                    panels,
                    texts,
                    backEquipmentPreviews,
                    iconDecorations,
                    clipRegions,
                    tooltipRegions,
                    navigationNodes,
                    capeGallery);
        }
        addPreviewCycleControls(widgets, previewBounds, height);
        widgets.add(ViewSpec.Widget.button(
                "editor.save",
                new Bounds(footerX, bottom, half, 20),
                UiMessage.info("nclskins.editor.save"),
                !busy && !name.trim().isEmpty()));
        widgets.add(ViewSpec.Widget.button(
                "editor.cancel",
                new Bounds(footerX + (footerWidth + 4) / 2, bottom, half, 20),
                UiMessage.info("gui.cancel"),
                !busy));

        texts.add(new ViewSpec.Text(
                "editor.title",
                new Bounds(0, 12, width, 10),
                UiMessage.info(originalPresetId.isPresent()
                        ? "nclskins.editor.edit_title"
                        : "nclskins.editor.add_title"),
                ViewSpec.Text.Alignment.CENTER));
        if (selectedEditorTab == EditorTab.APPEARANCE) {
            texts.add(new ViewSpec.Text(
                    "editor.name_label",
                    new Bounds(controlsX, CAPE_GALLERY_TOP - appearanceScroll, controlsWidth, 10),
                    UiMessage.info("nclskins.editor.name"),
                    ViewSpec.Text.Alignment.LEFT));
        }
        status.ifPresent(message -> texts.add(new ViewSpec.Text(
                "editor.status",
                new Bounds(24, Math.max(35, height - 48), Math.max(1, dividerX - 48), 10),
                message,
                ViewSpec.Text.Alignment.CENTER)));

        for (int index = 0; index < 2; index++) {
            ViewSpec.Widget tab = widgets.get(index);
            boolean selected = index == (selectedEditorTab == EditorTab.APPEARANCE ? 0 : 1);
            navigationNodes.add(new ViewSpec.NavigationNode(
                    tab.id(), tab.bounds(), Optional.of("editor.tabs"), index,
                    selected ? 0 : -1, tab.enabled(), ViewSpec.NavigationPattern.VERTICAL_LIST,
                    Optional.of(tab.id())));
        }
        int tabOrder = capeCatalog != null && selectedEditorTab == EditorTab.CAPE
                ? Math.max(4, navigationNodes.stream().mapToInt(ViewSpec.NavigationNode::tabOrder).max().orElse(3) + 1)
                : 2;
        for (ViewSpec.Widget widget : widgets) {
            if (widget.kind() != ViewSpec.WidgetKind.TAB_BUTTON && widget.kind() != ViewSpec.WidgetKind.CAPE_CARD
                    && navigationNodes.stream().noneMatch(node -> node.id().equals(widget.id()))) {
                int order = switch (widget.id()) {
                    case "editor.cape_search" -> 1;
                    case "editor.cape_filter" -> 2;
                    case "editor.cape_disclosure" -> 3;
                    default -> tabOrder++;
                };
                ViewSpec.NavigationNode node = ViewSpec.NavigationNode.control(widget, navigationNodes.size(), order);
                if (widget.id().equals("editor.name") || widget.id().equals("editor.catalog_info")) {
                    node = new ViewSpec.NavigationNode(node.id(), node.bounds(), Optional.of("editor.models"),
                            node.documentOrder(), node.tabOrder(), node.enabled(), node.pattern(), node.activationActionId());
                }
                navigationNodes.add(node);
                if (widget.id().equals("editor.name")) {
                    tabOrder++;
                }
            }
        }

        PreviewRenderer.CapeMode effectiveCapeMode = previewCapeId().isPresent()
                ? preview.capeMode()
                : PreviewRenderer.CapeMode.OFF;
        Optional<ViewSpec.CatalogImage> previewCatalogImage = catalogOrigin.map(origin ->
                new ViewSpec.CatalogImage(origin.collectionId(), origin.skinId()));
        ViewSpec.Preview previewSpec = new ViewSpec.Preview(
                "editor.preview",
                new Bounds(0, 0, width, height),
                previewBounds,
                skin,
                png.map(DraftPng::revision).orElseGet(() -> skin.optionalAssetId()
                        .map(id -> "asset:" + id)
                        .orElse("current-player")),
                variant,
                previewCapeId(),
                effectiveCapeMode,
                preview.outerLayerVisibility(),
                preview.yawDegrees(),
                preview.pitchDegrees(),
                preview.scale(),
                originalPresetId,
                previewCatalogImage,
                PreviewRenderer.PreviewIntent.EDITOR_DRAFT).withCapeElytra(capeCatalog == null || capeCatalog.previewHasElytra());
        return new ViewSpec(
                "preset_editor",
                UiMessage.info(originalPresetId.isPresent()
                        ? "nclskins.editor.edit_title"
                        : "nclskins.editor.add_title"),
                width,
                height,
                panels,
                texts,
                widgets,
                List.of(previewSpec),
                selectedEditorTab == EditorTab.CAPE ? capeGallery.scrollbar() : modelGallery.scrollbar(),
                List.of(new ViewSpec.TabGroup(
                        "editor.tabs", new Bounds(VerticalTabStyle.bounds(dividerX, 0).x(), 45, 24, 48),
                        List.of(
                                new ViewSpec.Tab("editor.tab.appearance", UiMessage.info("nclskins.editor.tab.appearance"), selectedEditorTab == EditorTab.APPEARANCE, !busy),
                                new ViewSpec.Tab("editor.tab.cape", UiMessage.info("nclskins.editor.tab.cape"), selectedEditorTab == EditorTab.CAPE, !busy)),
                        ViewSpec.TabOrientation.VERTICAL)),
                Optional.empty(),
                clipRegions,
                backEquipmentPreviews,
                iconDecorations,
                List.of(new ViewSpec.ScrollSurface(
                        selectedEditorTab == EditorTab.CAPE ? "editor.capes" : "editor.models",
                        selectedEditorTab == EditorTab.CAPE ? capeGallery.viewport() : modelGallery.viewport(),
                        ViewSpec.Scrollbar.Orientation.VERTICAL,
                        selectedEditorTab == EditorTab.CAPE ? capeGallery.position() : modelGallery.position(),
                        selectedEditorTab == EditorTab.CAPE ? capeGallery.maximum() : modelGallery.maximum())),
                tooltipRegions)
                .withNavigationNodes(navigationNodes);
    }

    public int maximumCapeScroll(int width, int height) {
        return maximumCapeScroll(width, height, ViewChromeMetrics.STANDARD);
    }

    public int maximumCapeScroll(int width, int height, ViewChromeMetrics chromeMetrics) {
        int dividerX = width / 2;
        return capeGalleryLayout(dividerX, width - dividerX, height, 0.0, chromeMetrics).maximum();
    }

    public double normalizedCapeScrollPosition(int width, int height, double desired) {
        return normalizedCapeScrollPosition(width, height, desired, ViewChromeMetrics.STANDARD);
    }

    public double normalizedCapeScrollPosition(
            int width, int height, double desired, ViewChromeMetrics chromeMetrics) {
        if (!Double.isFinite(desired)) {
            throw new IllegalArgumentException("cape scroll position must be finite");
        }
        return Math.max(0.0, Math.min(maximumCapeScroll(width, height, chromeMetrics), desired));
    }

    public double capePositionFromScrollbar(
            int width, int height, double desiredThumbTop) {
        return capePositionFromScrollbar(width, height, desiredThumbTop, ViewChromeMetrics.STANDARD);
    }

    public double capePositionFromScrollbar(
            int width, int height, double desiredThumbTop, ViewChromeMetrics chromeMetrics) {
        int dividerX = width / 2;
        EditorCardLayout layout = capeGalleryLayout(dividerX, width - dividerX, height, 0.0, chromeMetrics);
        if (layout.maximum() == 0 || layout.scrollbar().isEmpty()) {
            return 0.0;
        }
        ViewSpec.Scrollbar bar = layout.scrollbar().orElseThrow();
        int travel = Math.max(1, bar.track().height() - bar.thumb().height());
        double normalized = Math.max(
                0.0,
                Math.min(1.0, (desiredThumbTop - bar.track().y()) / travel));
        return normalized * layout.maximum();
    }

    public double initialCapeScrollPosition(int width, int height) {
        return initialCapeScrollPosition(width, height, ViewChromeMetrics.STANDARD);
    }

    public double initialCapeScrollPosition(int width, int height, ViewChromeMetrics chromeMetrics) {
        int dividerX = width / 2;
        EditorCardLayout layout = capeGalleryLayout(dividerX, width - dividerX, height, 0.0, chromeMetrics);
        int selected = selectedCapeIndex();
        CapeCatalogPresenter.CardPosition selectedPosition = capeCatalog == null
                ? new CapeCatalogPresenter.CardPosition(
                        selected / layout.columns() * (layout.cardHeight() + CatalogCardSizing.GAP), layout.cardHeight())
                : CapeCatalogPresenter.selectedPosition(capeCatalog, layout.columns(), layout.cardHeight());
        double centered = selectedPosition.top() - (layout.viewport().height() - selectedPosition.height()) / 2.0;
        return Math.max(0.0, Math.min(layout.maximum(), centered));
    }

    private void addPreviewCycleControls(
            List<ViewSpec.Widget> widgets,
            Bounds previewBounds,
            int height) {
        int size = 20;
        int gap = 2;
        int inset = gap;
        int x = previewBounds.x() + inset;
        if (hasCapePreview()) {
            widgets.add(ViewSpec.Widget.iconOnlyButton(
                    "editor.preview_mode",
                    new Bounds(x, 33 + inset, size, size),
                    previewModeLabel(),
                    preview.capeMode() == PreviewRenderer.CapeMode.ELYTRA
                            ? GuiIcon.APPEARANCE_BACK_ELYTRA
                            : GuiIcon.APPEARANCE_BACK_CAPE,
                    !busy));
        }

        int contentHeight = Math.max(0, height - 66);
        int stackHeight = size * 3 + gap * 2;
        int y = 33 + Math.max(0, (contentHeight - stackHeight) / 2);
        addOuterLayerCycleControl(widgets, "head", x, y, size);
        addOuterLayerCycleControl(widgets, "body", x, y + size + gap, size);
        addOuterLayerCycleControl(widgets, "legs", x, y + (size + gap) * 2, size);
    }

    private void addModelGallery(
            List<ViewSpec.Widget> widgets,
            List<ViewSpec.Panel> panels,
            List<ViewSpec.Text> texts,
            List<ViewSpec.IconDecoration> icons,
            List<ViewSpec.ClipRegion> clips,
            List<ViewSpec.NavigationNode> navigation,
            EditorCardLayout layout) {
        clips.add(new ViewSpec.ClipRegion("editor.models", layout.viewport(),
                List.of("editor.name", "editor.catalog_info", "editor.model_label",
                        "editor.model_card.", "editor.model_choice.")));
        for (int index = 0; index < 2; index++) {
            SkinVariant choice = index == 0 ? SkinVariant.CLASSIC : SkinVariant.SLIM;
            int cardWidth = layout.cardWidth() + (index == 1 ?
                    (layout.contentWidth() - CatalogCardSizing.GAP) % 2 : 0);
            Bounds card = new Bounds(layout.cardStartX() + index * (layout.cardWidth() + CatalogCardSizing.GAP),
                    MODEL_GALLERY_TOP - (int) Math.round(layout.position()),
                    cardWidth, (int) Math.round(cardWidth * (double) MODEL_CARD_ASPECT_HEIGHT / MODEL_CARD_ASPECT_WIDTH));
            String suffix = index == 0 ? "classic" : "slim";
            String id = "editor.model_choice." + suffix;
            String prefix = "editor.model_card." + suffix;
            boolean enabled = !busy && variantAvailable(choice);
            UiMessage label = UiMessage.info(index == 0
                    ? "nclskins.editor.arms_classic" : "nclskins.editor.arms_slim");
            navigation.add(ViewSpec.NavigationNode.card(id, card, "editor.models", index + 3,
                    choice == variant ? 3 : -1, enabled, ViewSpec.NavigationPattern.HORIZONTAL_LIST,
                    Optional.of(id)));
            if (!intersects(card, layout.viewport())) {
                continue;
            }
            panels.add(new ViewSpec.Panel(prefix, card, ViewSpec.Panel.Style.VANILLA_LIST));
            texts.add(new ViewSpec.Text(prefix + ".name",
                    CatalogCardGeometry.name(card),
                    label, ViewSpec.Text.Alignment.CENTER,
                    Optional.of(new ViewSpec.MarqueeActivation(card, List.of(id)))));
            icons.add(new ViewSpec.IconDecoration(prefix + ".icon",
                    SkinCardPreviewLayout.modelIcon(card),
                    index == 0 ? GuiIcon.APPEARANCE_MODEL_CLASSIC : GuiIcon.APPEARANCE_MODEL_SLIM,
                    id, enabled ? (choice == variant ? 1.0F : 0.8F) : 0.35F, enabled ? 1.0F : 0.35F));
            widgets.add(new ViewSpec.Widget(id, ViewSpec.WidgetKind.CAPE_CARD, card,
                    UiMessage.info(choice == variant ? "nclskins.editor.model_selected"
                            : enabled ? "nclskins.editor.model_available" : "nclskins.editor.model_unavailable", label),
                    Optional.of(choice == variant ? "selected" : "unselected"), Optional.empty(), enabled, true, 0));
        }
    }

    private EditorCardLayout modelGalleryLayout(int width, int height, double desired) {
        int paneX = width / 2;
        int contentWidth = Math.max(2, width - paneX - CollectionGridLayout.CONTENT_LEFT_INSET
                - CollectionGridLayout.CONTENT_RIGHT_INSET);
        Bounds viewport = new Bounds(paneX, 33,
                Math.max(1, width - paneX), Math.max(1, height - FOOTER_HEIGHT - 33));
        int cardWidth = Math.max(1, (contentWidth - CatalogCardSizing.GAP) / 2);
        int cardHeight = (int) Math.round((cardWidth + (contentWidth - CatalogCardSizing.GAP) % 2)
                * (double) MODEL_CARD_ASPECT_HEIGHT / MODEL_CARD_ASPECT_WIDTH);
        int contentHeight = MODEL_GALLERY_TOP - viewport.y() + cardHeight;
        int maximum = Math.max(0, contentHeight - viewport.height());
        double position = Math.max(0.0, Math.min(maximum, desired));
        Optional<ViewSpec.Scrollbar> scrollbar = maximum == 0 ? Optional.empty()
                : Optional.of(verticalScrollbar(width - CollectionGridLayout.SCROLLBAR_RIGHT_INSET,
                        viewport, contentHeight, position, maximum));
        return new EditorCardLayout(viewport, contentWidth, 2,
                paneX + CollectionGridLayout.CONTENT_LEFT_INSET, cardWidth, cardHeight,
                maximum, position, scrollbar);
    }

    public double normalizedModelScrollPosition(int width, int height, double desired) {
        if (!Double.isFinite(desired)) {
            throw new IllegalArgumentException("model scroll position must be finite");
        }
        return modelGalleryLayout(width, height, desired).position();
    }

    public double modelPositionFromScrollbar(int width, int height, double desiredThumbTop) {
        EditorCardLayout layout = modelGalleryLayout(width, height, 0.0);
        if (layout.scrollbar().isEmpty()) {
            return 0.0;
        }
        ViewSpec.Scrollbar bar = layout.scrollbar().orElseThrow();
        int travel = Math.max(1, bar.track().height() - bar.thumb().height());
        return Math.max(0.0, Math.min(1.0, (desiredThumbTop - bar.track().y()) / travel)) * layout.maximum();
    }

    private void addCapeGallery(
            List<ViewSpec.Widget> widgets,
            List<ViewSpec.Panel> panels,
            List<ViewSpec.Text> texts,
            List<ViewSpec.BackEquipmentPreview> backEquipmentPreviews,
            List<ViewSpec.IconDecoration> iconDecorations,
            List<ViewSpec.ClipRegion> clipRegions,
            List<ViewSpec.TooltipRegion> tooltipRegions,
            List<ViewSpec.NavigationNode> navigationNodes,
            EditorCardLayout layout) {
        if (capeCatalog != null) {
            CapeCatalogPresenter.present(capeCatalog, layout.viewport(), layout.cardWidth(), layout.cardHeight(),
                    layout.columns(), layout.position(), busy, backEquipmentMode(), widgets, panels, texts,
                    backEquipmentPreviews, iconDecorations, clipRegions, tooltipRegions, navigationNodes);
            return;
        }
        if (capeChoices.isEmpty()) {
            Bounds area = layout.viewport();
            texts.add(new ViewSpec.Text("editor.no_capes",
                    new Bounds(area.x(), area.y() + Math.max(0, (area.height() - 10) / 2), area.width(), 10),
                    UiMessage.info("nclskins.editor.no_capes"), ViewSpec.Text.Alignment.CENTER));
            return;
        }
        clipRegions.add(new ViewSpec.ClipRegion(
                "editor.capes",
                layout.viewport(),
                List.of("editor.cape_card.", "editor.cape_choice.")));
        int scroll = (int) Math.round(layout.position());
        for (int index = 0; index < capeChoices.size(); index++) {
            CapeChoice choice = capeChoices.get(index);
            int column = index % layout.columns();
            int row = index / layout.columns();
            Bounds card = new Bounds(
                    layout.cardStartX() + column * (layout.cardWidth() + CatalogCardSizing.GAP),
                    layout.viewport().y() + row * (layout.cardHeight() + CatalogCardSizing.GAP) - scroll,
                    layout.cardWidth(),
                    layout.cardHeight());
            String choiceWidgetId = "editor.cape_choice." + index;
            navigationNodes.add(ViewSpec.NavigationNode.card(
                    choiceWidgetId,
                    card,
                    "editor.capes",
                    index + 2,
                    index == selectedCapeIndex() ? 1 : -1,
                    !busy,
                    ViewSpec.NavigationPattern.GRID,
                    Optional.empty()));
            if (!intersects(card, layout.viewport())) {
                continue;
            }
            String prefix = "editor.cape_card." + index;
            panels.add(new ViewSpec.Panel(prefix, card, ViewSpec.Panel.Style.VANILLA_LIST));
            texts.add(new ViewSpec.Text(
                    prefix + ".name",
                    CatalogCardGeometry.name(card),
                    choice.label(),
                    ViewSpec.Text.Alignment.CENTER,
                    Optional.of(new ViewSpec.MarqueeActivation(card, List.of(choiceWidgetId)))));
            if (choice.id().isPresent()) {
                Bounds preview = CatalogCardGeometry.preview(card);
                backEquipmentPreviews.add(new ViewSpec.BackEquipmentPreview(
                        prefix + ".equipment",
                        preview,
                        choice.id().orElseThrow(),
                        backEquipmentMode()));
            } else {
                iconDecorations.add(new ViewSpec.IconDecoration(
                        prefix + ".empty",
                        CatalogCardGeometry.serviceIcon(CatalogCardGeometry.preview(card)),
                        GuiIcon.APPEARANCE_CAPE_NONE,
                        choiceWidgetId,
                        0.8F,
                        1.0F));
            }
            widgets.add(new ViewSpec.Widget(
                    choiceWidgetId,
                    ViewSpec.WidgetKind.CAPE_CARD,
                    card,
                    choice.id().isEmpty() ? choice.label() : capeLabel(choice.label()),
                    Optional.of(choice.id().equals(capeId) ? "selected" : "unselected"),
                    Optional.empty(),
                    !busy,
                    true,
                    0));
        }
    }

    private EditorCardLayout capeGalleryLayout(
            int paneX,
            int paneWidth,
            int height,
            double desiredPosition,
            ViewChromeMetrics chromeMetrics) {
        Objects.requireNonNull(chromeMetrics, "chromeMetrics");
        int galleryTop = capeCatalog == null ? CAPE_GALLERY_TOP : CAPE_GALLERY_TOP + 28 + CapeCatalogPresenter.errorHeight(capeCatalog, Math.max(1, paneWidth - CollectionGridLayout.CONTENT_LEFT_INSET - CollectionGridLayout.CONTENT_RIGHT_INSET));
        int viewportHeight = Math.max(1, height - FOOTER_HEIGHT - galleryTop);
        int contentWidth = Math.max(1, paneWidth
                - CollectionGridLayout.CONTENT_LEFT_INSET
                - CollectionGridLayout.CONTENT_RIGHT_INSET);
        Bounds viewport = new Bounds(
                paneX + CollectionGridLayout.CONTENT_LEFT_INSET,
                galleryTop,
                contentWidth,
                viewportHeight);
        CollectionGridLayout.CardMetrics metrics = CatalogCardSizing.pane(contentWidth, height, chromeMetrics);
        int columns = metrics.columns();
        int cardWidth = metrics.width();
        int cardHeight = capeCatalog == null ? CatalogCardGeometry.readOnlyHeight(metrics.height()) : metrics.height();
        int cardStartX = viewport.x();
        int rows = (capeChoices.size() + columns - 1) / columns;
        int totalHeight = capeCatalog == null ? (rows == 0 ? 0 : rows * (cardHeight + CatalogCardSizing.GAP) - CatalogCardSizing.GAP)
                : CapeCatalogPresenter.contentHeight(capeCatalog, columns, cardHeight);
        int maximum = Math.max(0, totalHeight - viewport.height());
        double position = Math.max(0.0, Math.min(maximum, desiredPosition));
        Optional<ViewSpec.Scrollbar> scrollbar = maximum == 0
                ? Optional.empty()
                : Optional.of(verticalScrollbar(
                        paneX + paneWidth - CollectionGridLayout.SCROLLBAR_RIGHT_INSET,
                        viewport,
                        totalHeight,
                        position,
                        maximum));
        return new EditorCardLayout(
                viewport,
                contentWidth,
                columns,
                cardStartX,
                cardWidth,
                cardHeight,
                maximum,
                position,
                scrollbar);
    }

    private static ViewSpec.Scrollbar verticalScrollbar(
            int x, Bounds viewport, int totalHeight, double position, int maximum) {
        Bounds track = new Bounds(x, viewport.y(), CAPE_SCROLLBAR_WIDTH, viewport.height());
        int thumbHeight = Math.max(12, (int) Math.round(
                track.height() * (viewport.height() / (double) totalHeight)));
        thumbHeight = Math.min(track.height(), thumbHeight);
        int travel = Math.max(0, track.height() - thumbHeight);
        int thumbY = track.y() + (int) Math.round(travel * (position / maximum));
        return new ViewSpec.Scrollbar(
                track,
                new Bounds(track.x(), thumbY, track.width(), Math.max(1, thumbHeight)),
                (int) Math.round(position),
                maximum,
                ViewSpec.Scrollbar.Orientation.VERTICAL);
    }

    private int selectedCapeIndex() {
        for (int index = 0; index < capeChoices.size(); index++) {
            if (capeChoices.get(index).id().equals(capeId)) {
                return index;
            }
        }
        return 0;
    }

    private static boolean intersects(Bounds candidate, Bounds viewport) {
        return candidate.right() > viewport.x()
                && candidate.x() < viewport.right()
                && candidate.bottom() > viewport.y()
                && candidate.y() < viewport.bottom();
    }

    private record EditorCardLayout(
            Bounds viewport,
            int contentWidth,
            int columns,
            int cardStartX,
            int cardWidth,
            int cardHeight,
            int maximum,
            double position,
            Optional<ViewSpec.Scrollbar> scrollbar) {}

    private void addOuterLayerCycleControl(
            List<ViewSpec.Widget> widgets,
            String id,
            int x,
            int y,
            int size) {
        EditorOuterLayerCycle.State state = EditorOuterLayerCycle.state(
                id, preview.outerLayerVisibility());
        UiMessage accessibleLabel = state.label();
        widgets.add(ViewSpec.Widget.iconOnlyButton(
                "editor.outer_layer." + id,
                new Bounds(x, y, size, size),
                accessibleLabel,
                state.icon(),
                !busy));
    }

    private UiMessage selectedCapeLabel() {
        CapeChoice selected = capeChoices.stream()
                .filter(choice -> choice.id().equals(capeId))
                .findFirst()
                .orElse(new CapeChoice(capeId, capeId
                        .map(PresetEditorModel::shortId)
                        .map(value -> UiMessage.literal(value, UiMessage.Severity.INFO))
                        .orElseGet(() -> UiMessage.info("nclskins.editor.no_cape"))));
        return selected.label();
    }

    private static Optional<String> catalogInfo(CatalogOrigin origin) {
        StringBuilder info = new StringBuilder();
        origin.description().ifPresent(info::append);
        origin.authors().ifPresent(authors -> {
            if (!info.isEmpty()) {
                info.append('\n');
            }
            info.append(authors);
        });
        return info.isEmpty() ? Optional.empty() : Optional.of(info.toString());
    }

    private UiMessage previewModeLabel() {
        return UiMessage.info(preview.capeMode() == PreviewRenderer.CapeMode.ELYTRA
                ? "item.minecraft.elytra"
                : "options.modelPart.cape");
    }

    private boolean hasCapePreview() {
        return capeCatalog == null ? capeId.isPresent() || hasOwnedCapePreview : previewCapeId().isPresent();
    }

    private BackEquipmentPreviewRenderer.Mode backEquipmentMode() {
        return preview.capeMode() == PreviewRenderer.CapeMode.ELYTRA
                ? BackEquipmentPreviewRenderer.Mode.ELYTRA
                : BackEquipmentPreviewRenderer.Mode.CAPE;
    }

    private PresetEditorModel copy(
            String nextName,
            SkinReference nextSkin,
            SkinVariant nextVariant,
            Optional<String> nextCapeId,
            Optional<DraftPng> nextPng,
            boolean nextBusy,
            Optional<UiMessage> nextStatus,
            PreviewInteractionModel nextPreview) {
        return new PresetEditorModel(
                originalPresetId,
                nextName,
                nextSkin,
                initialVariant,
                nextVariant,
                nextCapeId,
                capeChoices,
                hasOwnedCapePreview,
                nextPng,
                catalogOrigin,
                catalogVariants,
                reusableCatalogVariants,
                nextBusy,
                nextStatus,
                nextPreview, selectedEditorTab, capeCatalog);
    }

    private static SkinVariant variantFor(
            AccountState state, SkinReference reference, SkinVariant accountDefaultVariant) {
        return reference.optionalAssetId()
                .flatMap(id -> state.skinAssets().stream().filter(asset -> asset.id().equals(id)).findFirst())
                .map(SkinAsset::variant)
                .orElse(accountDefaultVariant);
    }

    private static List<CapeChoice> capeChoices(
            Optional<RemoteProfile> profile, Optional<String> selectedCapeId) {
        return capeChoices(profile, List.of(), selectedCapeId);
    }

    private static List<CapeChoice> capeChoices(
            Optional<RemoteProfile> profile,
            List<OwnedCapeEntry> cachedCapes,
            Optional<String> selectedCapeId) {
        if (profile.map(remote -> remote.capes().isEmpty()).orElseGet(cachedCapes::isEmpty)) {
            return List.of();
        }
        List<CapeChoice> choices = new ArrayList<>();
        choices.add(new CapeChoice(
                Optional.empty(),
                UiMessage.info("nclskins.editor.no_cape")));
        profile.ifPresent(remote -> remote.capes().forEach(cape -> choices.add(new CapeChoice(
                Optional.of(cape.id()),
                UiMessage.literal(
                        cape.optionalAlias().orElseGet(() -> shortId(cape.id())),
                        UiMessage.Severity.INFO)))));
        if (profile.isEmpty()) {
            cachedCapes.forEach(cape -> choices.add(new CapeChoice(
                    Optional.of(cape.id()),
                    UiMessage.literal(
                            cape.optionalAlias().orElseGet(() -> shortId(cape.id())),
                            UiMessage.Severity.INFO))));
        }
        return choices.stream().map(choice -> new CapeChoice(choice.id(), choice.label(),
                cachedCapes.stream().filter(cape -> choice.id().filter(cape.id()::equals).isPresent())
                        .map(OwnedCapeEntry::hasElytra).filter(Objects::nonNull).findFirst().orElse(null))).toList();
    }

    private static boolean hasOwnedCapePreview(
            Optional<RemoteProfile> profile,
            List<OwnedCapeEntry> cachedCapes) {
        return profile
                .map(remote -> !remote.capes().isEmpty())
                .orElseGet(() -> !cachedCapes.isEmpty());
    }

    private static String shortId(String value) {
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private static UiMessage capeLabel(Object value) {
        return UiMessage.info("nclskins.editor.cape", UiMessage.info("options.modelPart.cape"), value);
    }

    public record CapeChoice(Optional<String> id, UiMessage label, Boolean hasElytra) {
        public CapeChoice(Optional<String> id, UiMessage label) { this(id, label, null); }
        public CapeChoice {
            id = Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
        }
    }

    public record ReusableCatalogVariant(SkinReference skin, byte[] pngBytes) {
        public ReusableCatalogVariant {
            Objects.requireNonNull(skin, "skin");
            if (skin.optionalAssetId().isEmpty()) {
                throw new IllegalArgumentException("a reusable catalog variant requires an asset");
            }
            pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
        }

        @Override
        public byte[] pngBytes() {
            return pngBytes.clone();
        }
    }

    public static final class DraftPng {
        private final String fileName;
        private final byte[] bytes;
        private final String revision;

        public DraftPng(String fileName, byte[] bytes) {
            this.fileName = UntrustedDisplayName.sanitizePngFileName(fileName);
            this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
            this.revision = "draft:" + sha256(this.bytes);
        }

        public String fileName() {
            return fileName;
        }

        public byte[] bytes() {
            return bytes.clone();
        }

        public String revision() {
            return revision;
        }

        public String sourceName() {
            return UntrustedDisplayName.fromFileName(fileName, "Imported skin");
        }

        private static String sha256(byte[] value) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }
    }
}
