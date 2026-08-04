package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.CatalogOrigin;
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
    private static final int CAPE_GALLERY_TOP = 106;
    private static final int CAPE_CARD_GAP = 4;
    private static final int CAPE_CARD_HEIGHT = 86;
    private static final int CAPE_CARD_MIN_WIDTH = 68;
    private static final int CAPE_CARD_MAX_WIDTH = 88;
    private static final int CAPE_SCROLLBAR_WIDTH = 6;
    private static final int CAPE_SCROLLBAR_GAP = 4;
    private final Optional<UUID> originalPresetId;
    private final String name;
    private final SkinReference skin;
    private final SkinVariant initialVariant;
    private final SkinVariant variant;
    private final Optional<String> capeId;
    private final List<CapeChoice> capeChoices;
    private final Optional<DraftPng> png;
    private final Optional<CatalogOrigin> catalogOrigin;
    private final Map<SkinVariant, DraftPng> catalogVariants;
    private final Map<SkinVariant, SkinReference> reusableCatalogVariants;
    private final boolean busy;
    private final Optional<UiMessage> status;
    private final PreviewInteractionModel preview;

    private PresetEditorModel(
            Optional<UUID> originalPresetId,
            String name,
            SkinReference skin,
            SkinVariant initialVariant,
            SkinVariant variant,
            Optional<String> capeId,
            List<CapeChoice> capeChoices,
            Optional<DraftPng> png,
            Optional<CatalogOrigin> catalogOrigin,
            Map<SkinVariant, DraftPng> catalogVariants,
            Map<SkinVariant, SkinReference> reusableCatalogVariants,
            boolean busy,
            Optional<UiMessage> status,
            PreviewInteractionModel preview) {
        this.originalPresetId = Objects.requireNonNull(originalPresetId, "originalPresetId");
        this.name = Objects.requireNonNull(name, "name");
        this.skin = Objects.requireNonNull(skin, "skin");
        this.initialVariant = Objects.requireNonNull(initialVariant, "initialVariant");
        this.variant = Objects.requireNonNull(variant, "variant");
        this.capeId = Objects.requireNonNull(capeId, "capeId");
        this.capeChoices = List.copyOf(Objects.requireNonNull(capeChoices, "capeChoices"));
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
        if (busy) {
            return this;
        }
        SkinVariant next = variant == SkinVariant.CLASSIC ? SkinVariant.SLIM : SkinVariant.CLASSIC;
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
        if (busy || direction == 0) {
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
                preview.cycleCapeMode(capeId.isPresent()));
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
        if (busy) {
            return this;
        }
        DraftPng draft = new DraftPng(fileName, normalizedBytes);
        return new PresetEditorModel(
                originalPresetId,
                name,
                skin,
                initialVariant,
                variant,
                capeId,
                capeChoices,
                Optional.of(draft),
                Optional.empty(),
                Map.of(),
                Map.of(),
                false,
                Optional.of(UiMessage.info("nclskins.status.png_ready")),
                preview);
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
                personalSkinName);
    }

    public ViewSpec present(int width, int height) {
        return present(width, height, initialCapeScrollPosition(width, height));
    }

    public ViewSpec present(int width, int height, double capeScrollPosition) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("view dimensions must be positive");
        }
        int controlsWidth = Math.min(260, Math.max(154, width / 2 - 28));
        int controlsX = width - controlsWidth - 16;
        Bounds previewBounds = new Bounds(0, 0, Math.max(1, controlsX), Math.max(1, height));
        int half = Math.max(54, (controlsWidth - 4) / 2);
        int bottom = height - 28;

        List<ViewSpec.Panel> panels = new ArrayList<>();
        panels.add(new ViewSpec.Panel(
                "header", new Bounds(0, 0, width, 33), ViewSpec.Panel.Style.VANILLA_HEADER));
        panels.add(new ViewSpec.Panel(
                "footer", new Bounds(0, Math.max(0, height - 33), width, 33), ViewSpec.Panel.Style.VANILLA_FOOTER));
        List<ViewSpec.Widget> widgets = new ArrayList<>();
        List<ViewSpec.Text> texts = new ArrayList<>();
        widgets.add(ViewSpec.Widget.textField(
                "editor.name",
                new Bounds(controlsX, 55, controlsWidth, 20),
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
                        new Bounds(controlsX + controlsWidth - 16, 39, 16, 14),
                        UiMessage.literal(info, UiMessage.Severity.INFO),
                        !busy)));
        UiMessage modelLabel = UiMessage.info(variant == SkinVariant.CLASSIC
                ? "nclskins.editor.arms_classic"
                : "nclskins.editor.arms_slim");
        int modelWidth = controlsWidth;
        widgets.add(ViewSpec.Widget.button(
                "editor.model",
                new Bounds(controlsX, 82, modelWidth, 20),
                modelLabel,
                !busy && modelVariantSelectable()));
        List<ViewSpec.BackEquipmentPreview> backEquipmentPreviews = new ArrayList<>();
        List<ViewSpec.IconDecoration> iconDecorations = new ArrayList<>();
        List<ViewSpec.ClipRegion> clipRegions = new ArrayList<>();
        CapeGalleryLayout capeGallery = capeGalleryLayout(
                controlsX, controlsWidth, height, capeScrollPosition);
        addCapeGallery(
                widgets,
                panels,
                texts,
                backEquipmentPreviews,
                iconDecorations,
                clipRegions,
                capeGallery);
        addPreviewCycleControls(widgets, previewBounds, height);
        widgets.add(ViewSpec.Widget.button(
                "editor.save",
                new Bounds(controlsX, bottom, half, 20),
                UiMessage.info("nclskins.editor.save"),
                !busy && !name.trim().isEmpty()));
        widgets.add(ViewSpec.Widget.button(
                "editor.cancel",
                new Bounds(controlsX + (controlsWidth + 4) / 2, bottom, half, 20),
                UiMessage.info("gui.cancel"),
                !busy));

        texts.add(new ViewSpec.Text(
                "editor.title",
                new Bounds(0, 8, width, 12),
                UiMessage.info(originalPresetId.isPresent()
                        ? "nclskins.editor.edit_title"
                        : "nclskins.editor.add_title"),
                ViewSpec.Text.Alignment.CENTER));
        texts.add(new ViewSpec.Text(
                "editor.name_label",
                new Bounds(controlsX, 43, controlsWidth, 10),
                UiMessage.info("nclskins.editor.name"),
                ViewSpec.Text.Alignment.LEFT));
        status.ifPresent(message -> texts.add(new ViewSpec.Text(
                "editor.status",
                new Bounds(controlsX, 21, controlsWidth, 10),
                message,
                ViewSpec.Text.Alignment.CENTER)));

        PreviewRenderer.CapeMode effectiveCapeMode = capeId.isPresent()
                ? preview.capeMode()
                : PreviewRenderer.CapeMode.OFF;
        Optional<ViewSpec.CatalogImage> previewCatalogImage = catalogOrigin.map(origin ->
                new ViewSpec.CatalogImage(origin.collectionId(), origin.skinId()));
        ViewSpec.Preview previewSpec = new ViewSpec.Preview(
                "editor.preview",
                previewBounds,
                skin,
                png.map(DraftPng::revision).orElseGet(() -> skin.optionalAssetId()
                        .map(id -> "asset:" + id)
                        .orElse("current-player")),
                variant,
                capeId,
                effectiveCapeMode,
                preview.outerLayerVisibility(),
                preview.yawDegrees(),
                preview.pitchDegrees(),
                preview.scale(),
                originalPresetId,
                previewCatalogImage);
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
                capeGallery.scrollbar(),
                List.of(),
                Optional.empty(),
                clipRegions,
                backEquipmentPreviews,
                iconDecorations,
                capeGallery.maximum() <= 0
                        ? List.of()
                        : List.of(new ViewSpec.ScrollSurface(
                        "editor.capes",
                        capeGallery.viewport(),
                        ViewSpec.Scrollbar.Orientation.VERTICAL,
                        capeGallery.position(),
                        capeGallery.maximum())));
    }

    public int maximumCapeScroll(int width, int height) {
        int controlsWidth = Math.min(260, Math.max(154, width / 2 - 28));
        int controlsX = width - controlsWidth - 16;
        return capeGalleryLayout(controlsX, controlsWidth, height, 0.0).maximum();
    }

    public double normalizedCapeScrollPosition(int width, int height, double desired) {
        if (!Double.isFinite(desired)) {
            throw new IllegalArgumentException("cape scroll position must be finite");
        }
        return Math.max(0.0, Math.min(maximumCapeScroll(width, height), desired));
    }

    public double capePositionFromScrollbar(
            int width, int height, double desiredThumbTop) {
        int controlsWidth = Math.min(260, Math.max(154, width / 2 - 28));
        int controlsX = width - controlsWidth - 16;
        CapeGalleryLayout layout = capeGalleryLayout(controlsX, controlsWidth, height, 0.0);
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
        int controlsWidth = Math.min(260, Math.max(154, width / 2 - 28));
        int controlsX = width - controlsWidth - 16;
        CapeGalleryLayout layout = capeGalleryLayout(controlsX, controlsWidth, height, 0.0);
        int selected = selectedCapeIndex();
        int selectedTop = selected / layout.columns() * (CAPE_CARD_HEIGHT + CAPE_CARD_GAP);
        double centered = selectedTop - (layout.viewport().height() - CAPE_CARD_HEIGHT) / 2.0;
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
        if (capeId.isPresent()) {
            widgets.add(ViewSpec.Widget.iconButton(
                    "editor.preview_mode",
                    new Bounds(x, 33 + inset, size, size),
                    previewModeLabel(),
                    preview.capeMode() == PreviewRenderer.CapeMode.ELYTRA ? "elytra" : "cape",
                    !busy));
        }

        int contentHeight = Math.max(0, height - 66);
        int stackHeight = size * 3 + gap * 2;
        int y = 33 + Math.max(0, (contentHeight - stackHeight) / 2);
        addOuterLayerCycleControl(widgets, "head", x, y, size);
        addOuterLayerCycleControl(widgets, "body", x, y + size + gap, size);
        addOuterLayerCycleControl(widgets, "legs", x, y + (size + gap) * 2, size);
    }

    private void addCapeGallery(
            List<ViewSpec.Widget> widgets,
            List<ViewSpec.Panel> panels,
            List<ViewSpec.Text> texts,
            List<ViewSpec.BackEquipmentPreview> backEquipmentPreviews,
            List<ViewSpec.IconDecoration> iconDecorations,
            List<ViewSpec.ClipRegion> clipRegions,
            CapeGalleryLayout layout) {
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
                    layout.cardStartX() + column * (layout.cardWidth() + CAPE_CARD_GAP),
                    layout.viewport().y() + row * (CAPE_CARD_HEIGHT + CAPE_CARD_GAP) - scroll,
                    layout.cardWidth(),
                    CAPE_CARD_HEIGHT);
            if (!intersects(card, layout.viewport())) {
                continue;
            }
            String prefix = "editor.cape_card." + index;
            String choiceWidgetId = "editor.cape_choice." + index;
            panels.add(new ViewSpec.Panel(prefix, card, ViewSpec.Panel.Style.VANILLA_LIST));
            texts.add(new ViewSpec.Text(
                    prefix + ".name",
                    new Bounds(card.x() + 3, card.y() + 5, Math.max(1, card.width() - 6), 10),
                    choice.label(),
                    ViewSpec.Text.Alignment.CENTER));
            if (choice.id().isPresent()) {
                backEquipmentPreviews.add(new ViewSpec.BackEquipmentPreview(
                        prefix + ".equipment",
                        new Bounds(
                                card.x() + 6,
                                card.y() + 17,
                                Math.max(1, card.width() - 12),
                                Math.max(1, card.height() - 21)),
                        choice.id().orElseThrow(),
                        backEquipmentMode()));
            } else {
                iconDecorations.add(new ViewSpec.IconDecoration(
                        prefix + ".empty",
                        new Bounds(
                                card.x() + (card.width() - 15) / 2,
                                card.y() + (card.height() - 15) / 2,
                                15,
                                15),
                        "no_cape",
                        choiceWidgetId,
                        1.0F,
                        1.0F));
            }
            widgets.add(new ViewSpec.Widget(
                    choiceWidgetId,
                    ViewSpec.WidgetKind.CAPE_CARD,
                    card,
                    choice.label(),
                    Optional.of(choice.id().equals(capeId) ? "selected" : "unselected"),
                    Optional.of(UiMessage.info("nclskins.editor.cape", choice.label())),
                    !busy && !choice.id().equals(capeId),
                    true,
                    0));
        }
    }

    private CapeGalleryLayout capeGalleryLayout(
            int x, int width, int height, double desiredPosition) {
        int saveY = height - 28;
        int viewportHeight = Math.max(1, saveY - CAPE_GALLERY_TOP - 10);
        int contentWidth = Math.max(
                1, width - CAPE_SCROLLBAR_GAP - CAPE_SCROLLBAR_WIDTH);
        Bounds viewport = new Bounds(x, CAPE_GALLERY_TOP, contentWidth, viewportHeight);
        int columns = Math.max(1, Math.min(
                3,
                (contentWidth + CAPE_CARD_GAP)
                        / (CAPE_CARD_MIN_WIDTH + CAPE_CARD_GAP)));
        int cardWidth = Math.min(
                CAPE_CARD_MAX_WIDTH,
                Math.max(
                        1,
                        (contentWidth - (columns - 1) * CAPE_CARD_GAP) / columns));
        int rowWidth = columns * cardWidth + Math.max(0, columns - 1) * CAPE_CARD_GAP;
        int cardStartX = viewport.x() + Math.max(0, (contentWidth - rowWidth) / 2);
        int rows = (capeChoices.size() + columns - 1) / columns;
        int totalHeight = rows == 0
                ? 0
                : rows * CAPE_CARD_HEIGHT + (rows - 1) * CAPE_CARD_GAP;
        int maximum = Math.max(0, totalHeight - viewport.height());
        double position = Math.max(0.0, Math.min(maximum, desiredPosition));
        Optional<ViewSpec.Scrollbar> scrollbar = maximum == 0
                ? Optional.empty()
                : Optional.of(verticalScrollbar(
                        x + width - CAPE_SCROLLBAR_WIDTH,
                        viewport,
                        totalHeight,
                        position,
                        maximum));
        return new CapeGalleryLayout(
                viewport,
                columns,
                cardStartX,
                cardWidth,
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

    private record CapeGalleryLayout(
            Bounds viewport,
            int columns,
            int cardStartX,
            int cardWidth,
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
        UiMessage accessibleLabel = UiMessage.info(state.stateKey());
        widgets.add(ViewSpec.Widget.iconButton(
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
                        .map(value -> UiMessage.info("nclskins.editor.cape", value))
                        .orElseGet(() -> UiMessage.info(
                                "nclskins.editor.cape", UiMessage.info("nclskins.editor.no_cape")))));
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
        if (capeId.isEmpty()) {
            return UiMessage.info("nclskins.editor.no_cape");
        }
        return UiMessage.info(preview.capeMode() == PreviewRenderer.CapeMode.ELYTRA
                ? "nclskins.editor.preview_elytra"
                : "nclskins.editor.preview_cape");
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
                nextPng,
                catalogOrigin,
                catalogVariants,
                reusableCatalogVariants,
                nextBusy,
                nextStatus,
                nextPreview);
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
        if (selectedCapeId.isPresent()
                && choices.stream().noneMatch(choice -> choice.id().equals(selectedCapeId))) {
            String id = selectedCapeId.orElseThrow();
            choices.add(new CapeChoice(
                    Optional.of(id), UiMessage.info("nclskins.editor.cape", shortId(id))));
        }
        return List.copyOf(choices);
    }

    private static String shortId(String value) {
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    public record CapeChoice(Optional<String> id, UiMessage label) {
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
            String value = fileName.trim();
            if (value.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
                value = value.substring(0, value.length() - 4).trim();
            }
            if (value.isEmpty()) {
                return "Imported skin";
            }
            if (value.length() > 128) {
                value = value.substring(0, 128).trim();
            }
            return value.isEmpty() ? "Imported skin" : value;
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
