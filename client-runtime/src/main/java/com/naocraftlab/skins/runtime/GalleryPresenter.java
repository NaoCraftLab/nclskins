package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.compatibility.SkinCompatibility;
import com.naocraftlab.skins.core.compatibility.SkinCompatibilityStatus;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.service.PresetGalleryOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


public final class GalleryPresenter {
    public record DraftInput(Optional<UUID> selectedPresetId,
            com.naocraftlab.skins.client.OuterLayerVisibility baselineMask,
            com.naocraftlab.skins.client.OuterLayerVisibility draftMask, long generation,
            Optional<String> inspectionIdentity, java.util.Set<String> collapsedSections,
            Optional<String> temporarilyRevealedSection, PreviewRenderer.DisplayKind displayedBodyKind) {
        public DraftInput {
            Objects.requireNonNull(selectedPresetId, "selectedPresetId");
            Objects.requireNonNull(baselineMask, "baselineMask");
            Objects.requireNonNull(draftMask, "draftMask");
            Objects.requireNonNull(inspectionIdentity, "inspectionIdentity");
            Objects.requireNonNull(temporarilyRevealedSection, "temporarilyRevealedSection");
            Objects.requireNonNull(displayedBodyKind, "displayedBodyKind");
            collapsedSections = java.util.Set.copyOf(collapsedSections);
            collapsedSections.forEach(com.naocraftlab.skins.core.model.AccountUiPreferences::requireGallerySectionId);
            temporarilyRevealedSection.ifPresent(com.naocraftlab.skins.core.model.AccountUiPreferences::requireGallerySectionId);
            if (generation < 0) throw new IllegalArgumentException("negative draft generation");
        }

        public boolean dirty(Optional<UUID> activePresetId) {
            return !selectedPresetId.equals(Objects.requireNonNull(activePresetId, "activePresetId"))
                    || !baselineMask.equals(draftMask);
        }
    }

    private static final int CARD_WIDTH = 48;
    private static final int CARD_HEIGHT = 96;
    private static final int FOOTER_HEIGHT = 33;
    private static final int RECOVERY_BUTTON_WIDTH = 112;
    private static final int SESSION_STATE_TEXT_WIDTH = 84;
    private static final int RATE_LIMIT_PROGRESS_COLOR = 0xFF5A8FCB;
    private static final int RATE_LIMIT_PROGRESS_HEIGHT = 2;

    public ViewSpec present(ClientSnapshot s, int w, int h, int mx, int my, PreviewRenderer.CapeMode mode) {
        return present(s, w, h, mx, my, mode, SkinVariant.CLASSIC);
    }

    public ViewSpec present(ClientSnapshot s, int w, int h, int mx, int my, PreviewRenderer.CapeMode mode,
                            SkinVariant variant) {
        return present(s, w, h, mx, my, mode, variant, "", Optional.empty());
    }

    public ViewSpec present(ClientSnapshot s, int w, int h, int mx, int my, PreviewRenderer.CapeMode mode,
                            SkinVariant variant, String query, Optional<UUID> deleting) {
        return present(s, w, h, mx, my, mode, variant, query, deleting, s.galleryOffset());
    }

    public ViewSpec present(ClientSnapshot s, int w, int h, int mx, int my, PreviewRenderer.CapeMode mode,
                            SkinVariant variant, String query, Optional<UUID> deleting, double scroll) {
        return present(s, w, h, mx, my, mode, variant, query, deleting, scroll,
                normalizeSelectedCardId(s, query, null));
    }

    public ViewSpec present(ClientSnapshot s, int w, int h, int mx, int my, PreviewRenderer.CapeMode mode,
                            SkinVariant variant, String query, Optional<UUID> deleting, double scroll, String anchor) {
        return present(s, w, h, mx, my, mode, variant, query, deleting, scroll, anchor, defaultDraft(s));
    }

    public ViewSpec present(ClientSnapshot s, int w, int h, int mx, int my, PreviewRenderer.CapeMode mode,
                            SkinVariant variant, String query, Optional<UUID> deleting, double scroll, String anchor,
                            DraftInput draft) {
        return present(s,w,h,mx,my,mode,variant,query,deleting,scroll,anchor,draft,
                new ControlsInput(false, com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(),
                        PreviewInteractionModel.gallery().withCapeMode(mode), Optional.empty(), List.of(), 0, Optional.empty()));
    }

    public record CapeChoice(String id, UiMessage label, Optional<String> texture, boolean hasElytra, Content content) {
        public enum Content { TEXTURE, NATIVE, NONE }
        public CapeChoice(String id, UiMessage label, Optional<String> texture, boolean hasElytra) {
            this(id,label,texture,hasElytra,texture.isPresent()?Content.TEXTURE:Content.NONE);
        }
        public CapeChoice {
            Objects.requireNonNull(id); Objects.requireNonNull(label); Objects.requireNonNull(texture); Objects.requireNonNull(content);
            if ((content==Content.TEXTURE)!=texture.isPresent()) throw new IllegalArgumentException("Cape content/texture mismatch");
        }
    }

    public record LeftProjection(PlayerAppearanceStage.Appearance appearance,
            com.naocraftlab.skins.client.OuterLayerVisibility mask, DraftInput draft, ControlsInput controls,
            Optional<com.naocraftlab.skins.core.compatibility.SkinCompatibility> compatibility) {}

    public record ControlsInput(boolean controllerAvailable, com.naocraftlab.skins.client.OuterLayerVisibility liveOptions,
            PreviewInteractionModel transform, Optional<UUID> actionPresetId, List<CapeChoice> capes,
            double leftScroll, Optional<ViewSpec.BodySource> bodySource,
            com.naocraftlab.skins.client.PreviewAccessories accessories, Optional<LeftProjection> retained,
            boolean nativeCape, boolean nativeElytra) {
        public ControlsInput(boolean controllerAvailable, com.naocraftlab.skins.client.OuterLayerVisibility liveOptions,
                PreviewInteractionModel transform, Optional<UUID> actionPresetId, List<CapeChoice> capes,
                double leftScroll, Optional<ViewSpec.BodySource> bodySource,
                com.naocraftlab.skins.client.PreviewAccessories accessories, Optional<LeftProjection> retained) {
            this(controllerAvailable, liveOptions, transform, actionPresetId, capes, leftScroll, bodySource, accessories,
                    retained, accessories.canInspect() && accessories.cape(), accessories.canInspect() && accessories.elytra());
        }
        public ControlsInput(boolean controllerAvailable, com.naocraftlab.skins.client.OuterLayerVisibility liveOptions,
                PreviewInteractionModel transform, Optional<UUID> actionPresetId, List<CapeChoice> capes,
                double leftScroll, Optional<ViewSpec.BodySource> bodySource,
                com.naocraftlab.skins.client.PreviewAccessories accessories) {
            this(controllerAvailable, liveOptions, transform, actionPresetId, capes, leftScroll, bodySource, accessories, Optional.empty());
        }
        public ControlsInput(boolean controllerAvailable, com.naocraftlab.skins.client.OuterLayerVisibility liveOptions,
                PreviewInteractionModel transform, Optional<UUID> actionPresetId, List<CapeChoice> capes,
                double leftScroll, Optional<ViewSpec.BodySource> bodySource) {
            this(controllerAvailable, liveOptions, transform, actionPresetId, capes, leftScroll, bodySource,
                    com.naocraftlab.skins.client.PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LIVE_UNAVAILABLE));
        }
        public ControlsInput {
            Objects.requireNonNull(liveOptions); Objects.requireNonNull(transform); Objects.requireNonNull(actionPresetId);
            capes = List.copyOf(capes); Objects.requireNonNull(bodySource); Objects.requireNonNull(accessories);
            if (!Double.isFinite(leftScroll) || leftScroll < 0) throw new IllegalArgumentException("invalid stage offset");
        }
    }

    public ViewSpec present(ClientSnapshot s, int w, int h, int mx, int my, PreviewRenderer.CapeMode mode,
                            SkinVariant variant, String query, Optional<UUID> deleting, double scroll, String anchor,
                            DraftInput draft, ControlsInput controls) {
        Objects.requireNonNull(s, "snapshot");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(mode, "capeMode");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(deleting, "pendingDeleteId");
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("view dimensions must be positive");
        if (s.lifecycle() == ClientSnapshot.Lifecycle.INITIALIZING && s.account().isEmpty()) return coldLoadingView(w, h);
        Geometry g = geometry(w, h);
        var entries = positioned(s, query, draft, g);
        double maximum = Math.max(0, contentHeight(entries) - g.viewport().height() + 2);
        double offset = Math.max(0, Math.min(scroll, maximum));
        var panels = new ArrayList<ViewSpec.Panel>();
        panels.add(new ViewSpec.Panel("header", new Bounds(0, 0, w, 33), ViewSpec.Panel.Style.VANILLA_HEADER));
        panels.add(new ViewSpec.Panel("footer", new Bounds(0, Math.max(0, h - 33), w, 33), ViewSpec.Panel.Style.VANILLA_FOOTER));
        panels.add(new ViewSpec.Panel("gallery.workspace", new Bounds(g.leftWidth(),33,w-g.leftWidth(),Math.max(1,h-66)), ViewSpec.Panel.Style.VANILLA_TAB_CONTENT));
        var texts = new ArrayList<ViewSpec.Text>();
        var widgets = new ArrayList<ViewSpec.Widget>();
        var previews = new ArrayList<ViewSpec.Preview>();
        var cardClips = new ArrayList<ViewSpec.ClipRegion>();
        var nodes = new ArrayList<ViewSpec.NavigationNode>();
        widgets.add(ViewSpec.Widget.iconButton("gallery.providers", new Bounds(w - 28, 6, 20, 20),
                UiMessage.info("nclskins.providers.title"), GuiIcon.ACTION_PROVIDERS, !s.busy()));
        widgets.add(ViewSpec.Widget.iconButton("gallery.add", new Bounds(g.viewport().x(), 37, 20, 20),
                query.isBlank() || matchingPresetCount(s,query)>0 ? UiMessage.info("nclskins.gallery.add_hint") :
                        UiMessage.info("nclskins.gallery.create_named",UntrustedDisplayName.sanitize(query,"")), GuiIcon.ACTION_ADD_LOOK, !s.busy() && s.account().isPresent()));
        var sections = sections(s, query);
        boolean allCollapsed = !sections.isEmpty() && sections.stream().allMatch(section -> collapsed(draft,section.id()));
        int searchX = g.viewport().x() + 22;
        widgets.add(ViewSpec.Widget.textField("gallery.search", new Bounds(searchX, 37,
                Math.max(1, g.viewport().right() - searchX - 22), 20),
                UiMessage.info("nclskins.gallery.search"), query, UiMessage.info("nclskins.gallery.search_hint"),
                !s.busy(), 128, true, Optional.empty()));
        widgets.add(ViewSpec.Widget.iconButton("gallery.sections", new Bounds(g.viewport().right() - 20, 37, 20, 20),
                UiMessage.info(allCollapsed ? "nclskins.collection.expand_all" : "nclskins.collection.collapse_all"),
                allCollapsed ? GuiIcon.ACTION_EXPAND_ALL : GuiIcon.ACTION_COLLAPSE_ALL, !s.busy() && !sections.isEmpty()));
        var recovery = addGlobalWidgets(s, w, h, widgets);
        addHeader(s, w, recovery, texts);
        if (draft.selectedPresetId().isPresent() && draft.dirty(s.activePresetId())) {
            widgets.removeIf(widget -> widget.id().equals("gallery.done"));
            int buttonWidth = Math.min(100, Math.max(1,(w-20)/3));
            int x = (w-(buttonWidth*3+8))/2;
            for (String action : List.of("apply","back","reset")) {
                widgets.add(ViewSpec.Widget.button("gallery."+action,new Bounds(x,ViewChromeMetrics.STANDARD.footerRowTop(h,20),buttonWidth,20),
                        UiMessage.info(action.equals("apply") ? "nclskins.gallery.apply" : action.equals("back") ? "gui.back" : "controls.reset"),
                        action.equals("apply") ? rateLimitHint(s,true) : Optional.empty(), !s.busy()));
                x += buttonWidth+4;
            }
        }
        var equipment = new ArrayList<ViewSpec.BackEquipmentPreview>();
        var decorations = new ArrayList<ViewSpec.IconDecoration>();
        Bounds stageViewport = new Bounds(4,36,Math.max(1,g.leftWidth()-6),Math.max(1,h-73));
        int oldLeft = Math.min(Math.max(64,(h-73)/4)+6,Math.max(70,w-60));
        int stageWidth = Math.max(1,Math.min(Math.max(1,(h-73)/4),oldLeft-6));
        int stageHeight = stageWidth*2;
        int stageOffset = (int)Math.round(controls.leftScroll());
        int areaHeight = (int)Math.round(stageHeight*1.1);
        Bounds stage = new Bounds(stageViewport.x(),stageViewport.y(),stageViewport.width(),areaHeight);
        Bounds lowerViewport = new Bounds(stageViewport.x(),stage.bottom(),stageViewport.width(),Math.max(1,h-33-stage.bottom()));
        int contentX = lowerViewport.x()+8;
        int contentWidth = Math.max(1,lowerViewport.width()-16);
        Bounds fit = new Bounds(stage.x()+(stage.width()-stageWidth)/2,stage.y()+(stage.height()-stageHeight)/2,stageWidth,stageHeight);
        var leftDraft = controls.retained().map(LeftProjection::draft).orElse(draft);
        var leftControls = controls.retained().map(LeftProjection::controls).orElse(controls);
        var selected = s.account().stream().flatMap(a -> a.presets().stream()).filter(p -> leftDraft.selectedPresetId().filter(p.id()::equals).isPresent()).findFirst();
        var mask = controls.retained().map(LeftProjection::mask).orElseGet(() -> selected.isPresent() ? leftDraft.draftMask() : leftControls.liveOptions());
        var appearance = controls.retained().map(LeftProjection::appearance).orElseGet(() -> mainAppearance(s, leftDraft, variant, leftControls.capes(),
                leftControls.nativeCape() || leftControls.nativeElytra(), leftControls.nativeElytra()));
        var selectedCape = appearance.capeId();
        var main = PlayerAppearanceStage.present("gallery.preview",w,h,fit,appearance,controls.transform().withOuterLayerVisibility(mask));
        boolean nativeAccessories = nativeAccessories(s, leftDraft, leftControls.capes(),
                leftControls.nativeCape() || leftControls.nativeElytra());
        if (nativeAccessories) main = main.withNativeAccessories(true, controls.transform().capeMode());
        previews.add(main.withBodySource(leftControls.bodySource()));
        if (selectedCape.isPresent() || nativeAccessories) {
            var control = PlayerAppearanceStage.capeMode("gallery.preview_mode",controls.transform(),!s.busy()
                    && (selectedCape.isPresent() || controls.accessories().canInspect()));
            widgets.add(ViewSpec.Widget.iconOnlyButton(control.id(),new Bounds(stage.x()+2,stage.y()+2,20,20),control.label(),control.icon(),control.enabled()));
        }
        controls.retained().map(LeftProjection::compatibility).orElseGet(() -> selected.map(s::compatibilityFor))
                .filter(c -> c.status()!=SkinCompatibilityStatus.ORDINARY).ifPresent(c ->
                widgets.add(AppearanceCard.compatibility("gallery.preview.compatibility",stage,stage.bottom()-2,c)));
        int leftY = stage.bottom()+4-stageOffset;
        if (leftControls.controllerAvailable() && leftDraft.displayedBodyKind()==PreviewRenderer.DisplayKind.ORDINARY) {
            texts.add(new ViewSpec.Text("gallery.layers",CollectionHeaderStyle.flushContentBounds(new Bounds(contentX,leftY,contentWidth,10)),UiMessage.info("nclskins.gallery.layers"),ViewSpec.Text.Alignment.LEFT,ViewSpec.Text.Layout.COLLECTION_HEADER));
            leftY+=14;
            int index=0;
            for(var control : PlayerAppearanceStage.outerLayers(mask,"gallery.outer_layer.",!s.busy())) {
                widgets.add(ViewSpec.Widget.iconOnlyButton(control.id(),new Bounds(stageViewport.x()+(stageViewport.width()-64)/2+index*22,leftY,20,20),control.label(),control.icon(),control.enabled()));index++;
            }
            leftY+=24;
        }
        if (!leftControls.capes().isEmpty()) {
            texts.add(new ViewSpec.Text("gallery.capes",CollectionHeaderStyle.flushContentBounds(new Bounds(contentX,leftY,contentWidth,10)),UiMessage.info("nclskins.providers.capes"),ViewSpec.Text.Alignment.LEFT,ViewSpec.Text.Layout.COLLECTION_HEADER));leftY+=14;
            int capeColumns=Math.max(1,(contentWidth+2)/34);
            int capeSize=32;
            var markedCape=leftDraft.inspectionIdentity().or(() -> nativeAccessories ? Optional.of("CPM")
                    : leftControls.capes().stream().filter(c -> c.texture().isPresent()).map(CapeChoice::id).findFirst()
                            .or(() -> leftControls.capes().stream().filter(c -> c.id().equals("none")).map(CapeChoice::id).findFirst()));
            for(int i=0;i<leftControls.capes().size();i++) {
                int rowCount=Math.min(capeColumns,leftControls.capes().size()-(i/capeColumns)*capeColumns);
                int rowWidth=rowCount*(capeSize+2)-2;
                var c=leftControls.capes().get(i);Bounds b=new Bounds(contentX+(contentWidth-rowWidth)/2+(i%capeColumns)*(capeSize+2),leftY+(i/capeColumns)*(capeSize+2),capeSize,capeSize);
                var card=new ViewSpec.Widget("gallery.inspect."+c.id(),ViewSpec.WidgetKind.CAPE_CARD,b,c.label(),markedCape.filter(c.id()::equals).filter(id -> leftControls.capes().size()>1 || c.content()!=CapeChoice.Content.NONE).map(id -> "active"),Optional.of(c.label()),!s.busy(),true,0);
                widgets.add(c.content()==CapeChoice.Content.NATIVE ? card.withActivation(!s.busy()&&leftControls.accessories().canInspect(),null) : card);
                if(c.texture().isEmpty()) decorations.add(new ViewSpec.IconDecoration("gallery.inspect."+c.id()+(c.content()==CapeChoice.Content.NATIVE ? ".icon" : ".none"),b,c.content()==CapeChoice.Content.NATIVE ? GuiIcon.SOURCE_CPM : GuiIcon.APPEARANCE_CAPE_NONE,"gallery.inspect."+c.id(),0.8F,1.0F));
                if(c.texture().isPresent()) equipment.add(new AppearanceCard.Cape(c.texture().orElseThrow(),com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode.CAPE,c.hasElytra()).present("gallery.inspect."+c.id()+".preview",b).asProviderThumbnail());
            }
            leftY+=((leftControls.capes().size()+capeColumns-1)/capeColumns)*(capeSize+2);
        }
        if (controls.retained().isPresent()) widgets.replaceAll(widget -> widget.id().startsWith("gallery.outer_layer.")
                || widget.id().startsWith("gallery.inspect.") || widget.id().equals("gallery.preview_mode")
                        && !Objects.equals(leftControls.bodySource(),controls.bodySource())
                ? widget.withActivation(false, null) : widget);
        double leftMaximum=Math.max(0,leftY+stageOffset-lowerViewport.bottom());
        if (controls.leftScroll()>leftMaximum) return present(s,w,h,mx,my,mode,variant,query,deleting,scroll,anchor,draft,
                new ControlsInput(controls.controllerAvailable(),controls.liveOptions(),controls.transform(),controls.actionPresetId(),controls.capes(),leftMaximum,controls.bodySource(),controls.accessories(),controls.retained(),controls.nativeCape(),controls.nativeElytra()));
        int tab = 0;
        if (recovery.isPresent()) tab = addControl(nodes, widgets, recovery.orElseThrow().widgetId(), tab);
        tab = addControl(nodes, widgets, "gallery.add", tab);
        tab = addControl(nodes, widgets, "gallery.search", tab);
        tab = addControl(nodes, widgets, "gallery.sections", tab);

        for (Entry entry : entries) {
            Bounds b = new Bounds(g.viewport().x() + entry.bounds().x(),
                    g.viewport().y() + entry.bounds().y() - (int)Math.round(offset), entry.bounds().width(), entry.bounds().height());
            boolean visible = AppearanceCollections.intersects(b, g.viewport());
            if (entry.preset().isEmpty()) {
                if (visible) widgets.add(ViewSpec.Widget.collectionHeader(entry.id(), b,
                        UiMessage.info("nclskins.capes.collection", collapsed(draft, entry.id()) ? "▶" : "▼",
                                UiMessage.info(entry.id().equals("gallery.favorites") ? "nclskins.gallery.favorites" : "nclskins.gallery.your_appearances")),
                        !s.busy(), false));
                nodes.add(AppearanceCollections.navigation(entry.id(), b, "gallery.cards", nodes.size(), tab++,
                        !s.busy(), ViewSpec.NavigationPattern.GRID, Optional.of(entry.id())));
            } else {
                var preset = entry.preset().orElseThrow();
                boolean menu = controls.actionPresetId().filter(preset.id()::equals).isPresent()
                        || deleting.filter(preset.id()::equals).isPresent();
                nodes.add(AppearanceCollections.navigation(entry.id(), b, "gallery.cards", nodes.size(),
                        tab++, !s.busy() && s.account().isPresent() && deleting.isEmpty() && !menu,
                        ViewSpec.NavigationPattern.GRID, Optional.of(entry.id())));
                if (visible) {
                    var chrome = AppearanceCard.chrome(entry.id(), entry.id(), b, ViewSpec.WidgetKind.CATALOG_CARD,
                            UiMessage.literal(preset.name(), UiMessage.Severity.INFO), draft.selectedPresetId().filter(preset.id()::equals).isPresent() ? Optional.of(s.activePresetId().filter(preset.id()::equals).isPresent() ? "active" : "selected")
                                    : s.activePresetId().filter(preset.id()::equals).isPresent() ? Optional.of("active") : menu ? Optional.of("menu") : Optional.empty(), !s.busy() && deleting.isEmpty() && !menu);
                    panels.add(chrome.surface());
                    panels.add(new ViewSpec.Panel("gallery.preset."+preset.id()+".namebar",
                            menu ? new Bounds(b.x(),b.y()+2,b.width(),b.height()-4)
                                    : new Bounds(b.x(),b.bottom()-16,b.width(),16),ViewSpec.Panel.Style.TRANSLUCENT_NAMEBAR));
                    int clipLeft=Math.max(b.x(),g.viewport().x()), clipTop=Math.max(b.y(),g.viewport().y());
                    cardClips.add(AppearanceCollections.clip("gallery.preset."+preset.id()+".preview.clip",
                            new Bounds(clipLeft,clipTop,Math.min(b.right(),g.viewport().right())-clipLeft,
                                    Math.min(b.bottom(),g.viewport().bottom())-clipTop),List.of("gallery.preset."+preset.id()+".preview")));
                    widgets.add(menu ? chrome.widget().withActivation(false, null) : chrome.widget());
                    addCard(s, preset, b, variant, controls.transform().capeMode(), menu, texts, widgets, previews);
                    if (menu)
                        cardActions(s,preset,b,deleting.filter(preset.id()::equals).isPresent(),texts,widgets);
                }
                var children = new ArrayList<ViewSpec.Widget>();
                addCard(s,preset,b,variant,controls.transform().capeMode(),menu,new ArrayList<>(),children,new ArrayList<>());
                if (menu) cardActions(s,preset,b,deleting.filter(preset.id()::equals).isPresent(),new ArrayList<>(),children);
                for (var widget : children) {
                    var control = ViewSpec.NavigationNode.control(widget,nodes.size(),tab++);
                    nodes.add(new ViewSpec.NavigationNode(control.id(),control.bounds(),Optional.of("gallery.cards"),
                            control.documentOrder(),control.tabOrder(),control.enabled(),control.pattern(),control.activationActionId()));
                }
            }
        }
        for (var widget : widgets) if (widget.id().startsWith("gallery.outer_layer.") || widget.id().startsWith("gallery.inspect.")
                || widget.id().equals("gallery.preview_mode") || widget.id().equals("gallery.preview.compatibility")) {
            var node=ViewSpec.NavigationNode.control(widget,nodes.size(),tab++);
            nodes.add(new ViewSpec.NavigationNode(node.id(),node.bounds(),Optional.of("gallery.stage"),node.documentOrder(),node.tabOrder(),node.enabled(),node.pattern(),node.activationActionId()));
        }
        tab = addControl(nodes, widgets, "gallery.providers", tab);
        for(String id : List.of("gallery.done","gallery.apply","gallery.back","gallery.reset")) tab=addControl(nodes,widgets,id,tab);
        Bounds track = new Bounds(w-6, g.viewport().y(), 6, g.viewport().height());
        int thumbHeight = maximum == 0 ? track.height() : Math.min(track.height(), Math.max(12,
                (int)(track.height() * track.height() / (track.height() + maximum))));
        Bounds thumb = new Bounds(track.x(), track.y() + (maximum == 0 ? 0 : (int)Math.round((track.height()-thumbHeight)*offset/maximum)), track.width(), thumbHeight);
        cardClips.add(AppearanceCollections.clip("gallery.cards", g.viewport(),
                List.of("gallery.card.", "gallery.preset.", "gallery.favorites", "gallery.your_appearances")));
        cardClips.add(AppearanceCollections.clip("gallery.stage",lowerViewport,List.of("gallery.layers","gallery.outer_layer.","gallery.capes","gallery.inspect.")));
        return new ViewSpec("gallery", UiMessage.info("nclskins.gallery.title"), w, h, panels, texts, widgets, previews,
                Optional.of(new ViewSpec.Scrollbar(track, thumb, (int)Math.round(offset), (int)maximum, ViewSpec.Scrollbar.Orientation.VERTICAL)),
                List.of(), Optional.empty(), cardClips, equipment, decorations,
                List.of(new ViewSpec.ScrollSurface("gallery.cards", g.viewport(), ViewSpec.Scrollbar.Orientation.VERTICAL, offset, maximum),
                        new ViewSpec.ScrollSurface("gallery.stage",lowerViewport,ViewSpec.Scrollbar.Orientation.VERTICAL,Math.min(controls.leftScroll(),leftMaximum),leftMaximum)),
                List.of(), rateLimitProgressDecorations(s, widgets)).withNavigationNodes(nodes);
    }

    private static void addCard(ClientSnapshot s, AppearancePreset p, Bounds b, SkinVariant variant,
                                PreviewRenderer.CapeMode mode, boolean menu, List<ViewSpec.Text> texts,
                                List<ViewSpec.Widget> widgets, List<ViewSpec.Preview> previews) {
        String prefix = "gallery.preset." + p.id();
        var cape = cape(s, p);
        if (!menu) previews.add(new AppearanceCard.Skin(p.skin(), revision(p), variantFor(s.account().orElseThrow(), p, variant),
                cape, cape.isPresent() ? mode : PreviewRenderer.CapeMode.OFF, p.outerLayerVisibility(), -25, 0, 0.88F,
                Optional.of(p.id()), Optional.empty(), Optional.empty(), PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL,
                p.offlineCape() == null || p.offlineCape().hasElytra()).present(prefix + ".preview", b));
        int barTop = menu ? b.y() : b.bottom()-16;
        int nameTop=barTop+(menu ? 5 : 4);
        texts.add(new ViewSpec.Text(prefix + ".name", new Bounds(b.x()+4,nameTop,b.width()-18,10),
                UiMessage.literal(p.name(), UiMessage.Severity.INFO), ViewSpec.Text.Alignment.LEFT,
                Optional.of(new ViewSpec.MarqueeActivation(b, List.of("gallery.card."+p.id(),prefix+".more"),
                        Optional.of(new Bounds(b.x(),nameTop,b.width()-14,10))))));
        widgets.add(new ViewSpec.Widget(prefix + ".more", ViewSpec.WidgetKind.TRANSPARENT_BUTTON,
                new Bounds(b.x(),barTop,b.width(),16), UiMessage.info("nclskins.gallery.actions"),
                Optional.of(menu ? "down" : "up"), Optional.empty(), !s.busy(), true, 0));
        var compatibility = s.compatibilityFor(p);
        if (!menu && compatibility.status() != SkinCompatibilityStatus.ORDINARY)
            widgets.add(ViewSpec.Widget.compatibilityIndicator(prefix + ".compatibility", new Bounds(b.x(),b.y()+2,20,20),
                    CompatibilityMessages.accessibleLabel(compatibility), CompatibilityMessages.icon(compatibility)));
    }

    private static void cardActions(ClientSnapshot s,AppearancePreset p,Bounds b,boolean deleting,List<ViewSpec.Text> texts,List<ViewSpec.Widget> widgets) {
        String prefix="gallery.preset."+p.id();
        int x=b.x()+2;int y=b.bottom()-44;
        int leftWidth=(b.width()-6)/2;
        int rightX=x+leftWidth+2;
        int rightWidth=b.right()-2-rightX;
        if(deleting) {
            int promptTop = (b.y()+16+y-20)/2;
            texts.add(new ViewSpec.Text(prefix+".confirmation",new Bounds(b.x()+2,promptTop,b.width()-4,20),
                    UiMessage.info("nclskins.gallery.delete_confirmation"),ViewSpec.Text.Alignment.CENTER,ViewSpec.Text.Layout.WRAP));
            widgets.add(ViewSpec.Widget.button(prefix+".delete_confirm",new Bounds(b.x()+2,y,b.width()-4,20),UiMessage.info("nclskins.gallery.delete"),!s.busy()));
            widgets.add(ViewSpec.Widget.button(prefix+".delete_cancel",new Bounds(b.x()+2,y+22,b.width()-4,20),UiMessage.info("gui.cancel"),!s.busy()));
        } else {
            widgets.add(ViewSpec.Widget.button(prefix+".favorite",new Bounds(x,y,leftWidth,20),UiMessage.literal(p.favorite() ? "★" : "☆",UiMessage.Severity.INFO),
                    Optional.of(UiMessage.info(p.favorite() ? "nclskins.gallery.unfavorite" : "nclskins.gallery.favorite")),!s.busy()));
            widgets.add(ViewSpec.Widget.iconButton(prefix+".edit",new Bounds(rightX,y,rightWidth,20),UiMessage.info("nclskins.gallery.edit"),GuiIcon.ACTION_EDIT,!s.busy()));
            widgets.add(ViewSpec.Widget.iconButton(prefix+".duplicate",new Bounds(x,y+22,leftWidth,20),UiMessage.info("nclskins.gallery.duplicate"),GuiIcon.ACTION_DUPLICATE,!s.busy()));
            widgets.add(ViewSpec.Widget.iconButton(prefix+".delete",new Bounds(rightX,y+22,rightWidth,20),UiMessage.info("nclskins.gallery.delete"),GuiIcon.ACTION_DELETE,!s.busy()));
        }
    }

    private static int addControl(List<ViewSpec.NavigationNode> nodes, List<ViewSpec.Widget> widgets, String id, int tab) {
        var widget = widgets.stream().filter(w -> w.id().equals(id)).findFirst();
        if (widget.isPresent()) nodes.add(ViewSpec.NavigationNode.control(widget.orElseThrow(), nodes.size(), tab++));
        return tab;
    }

    static PlayerAppearanceStage.Appearance mainAppearance(ClientSnapshot s, DraftInput draft,
            SkinVariant variant, List<CapeChoice> capes, boolean nativePresent, boolean nativeElytra) {
        var selected = s.account().stream().flatMap(a -> a.presets().stream())
                .filter(p -> draft.selectedPresetId().filter(p.id()::equals).isPresent()).findFirst();
        var inspection = capes.stream().filter(c -> draft.inspectionIdentity().filter(c.id()::equals).isPresent()).findFirst();
        var winner = capes.stream().filter(c -> c.texture().isPresent()).findFirst();
        boolean nativeWinner = nativeAccessories(s, draft, capes, nativePresent);
        var selectedCape = draft.inspectionIdentity().isPresent() ? inspection.flatMap(CapeChoice::texture)
                : nativeWinner ? Optional.<String>empty() : winner.flatMap(CapeChoice::texture)
                        .or(() -> capes.isEmpty() ? selected.flatMap(p -> cape(s, p)) : Optional.empty());
        return new PlayerAppearanceStage.Appearance(selected.map(AppearancePreset::skin)
                .orElse(com.naocraftlab.skins.core.model.SkinReference.accountDefault()),
                selected.map(GalleryPresenter::revision).orElse("current-player"),
                selected.map(p -> variantFor(s.account().orElseThrow(), p, variant)).orElse(variant),
                selectedCape, selected.map(AppearancePreset::id), Optional.empty(), nativeWinner ? nativeElytra : inspection.or(() -> winner).map(CapeChoice::hasElytra)
                        .orElseGet(() -> selected.map(p -> p.offlineCape() == null || p.offlineCape().hasElytra()).orElse(true)),
                PreviewRenderer.PreviewIntent.EDITOR_DRAFT);
    }

    static boolean nativeAccessories(ClientSnapshot s, DraftInput draft, List<CapeChoice> capes, boolean nativePresent) {
        if (!nativePresent || !s.providers().cape().enabled(com.naocraftlab.skins.core.provider.BuiltinProvider.CPM)) return false;
        if (draft.inspectionIdentity().isPresent()) return draft.inspectionIdentity().filter("CPM"::equals).isPresent();
        var order = s.providers().cape().order();
        int nativePriority = order.indexOf(com.naocraftlab.skins.core.provider.BuiltinProvider.CPM);
        if (nativePriority < 0) return false;
        var winner = capes.stream().filter(c -> c.texture().isPresent()).findFirst();
        if (winner.isPresent()) return nativePriority < order.stream().map(Enum::name).toList().indexOf(winner.orElseThrow().id());
        return s.account().stream().flatMap(a -> a.presets().stream())
                .filter(p -> draft.selectedPresetId().filter(p.id()::equals).isPresent()).findFirst().flatMap(p -> cape(s, p)).isEmpty();
    }

    private static String revision(AppearancePreset p) { return p.skin().optionalAssetId().map(id -> "asset:"+id).orElse("provider:default"); }

    private static Optional<String> cape(ClientSnapshot s, AppearancePreset p) {
        for (var provider : s.providers().cape().order()) {
            if (!s.providers().cape().enabled(provider)) continue;
            if (provider == com.naocraftlab.skins.core.provider.BuiltinProvider.OFFLINE && p.offlineCape()!=null)
                return Optional.of("provider:cape:"+p.offlineCape().sha256());
            if (provider == com.naocraftlab.skins.core.provider.BuiltinProvider.MINECRAFT && p.capeId()!=null) return p.optionalCapeId();
        }
        return Optional.empty();
    }

    private static DraftInput defaultDraft(ClientSnapshot s) {
        var mask = s.account().stream().flatMap(a -> a.presets().stream()).filter(p -> s.activePresetId().filter(p.id()::equals).isPresent())
                .findFirst().map(AppearancePreset::outerLayerVisibility).orElse(com.naocraftlab.skins.client.OuterLayerVisibility.allVisible());
        return new DraftInput(s.activePresetId(), mask, mask, 0, Optional.empty(), java.util.Set.of(), Optional.empty(), PreviewRenderer.DisplayKind.UNCONFIRMED);
    }

    private static boolean collapsed(DraftInput d, String id) {
        return d.collapsedSections().contains(id) && !d.temporarilyRevealedSection().filter(id::equals).isPresent();
    }

    private static List<AppearancePreset> ordered(Optional<AccountState> account, String query) {
        String q = Objects.requireNonNull(query,"query").trim().toLowerCase(java.util.Locale.ROOT);
        return PresetGalleryOrder.arrange(account.stream().flatMap(a -> a.presets().stream())
                .filter(p -> p.name().toLowerCase(java.util.Locale.ROOT).contains(q)).toList());
    }

    private static List<Entry> positioned(ClientSnapshot s, String query, DraftInput draft, Geometry g) {
        return positioned(sections(s,query),draft,g);
    }

    private static List<Entry> positioned(List<Section> sections, DraftInput draft, Geometry g) {
        var entries = new ArrayList<Entry>();
        int y = 2;
        for (var section : sections) {
            var group = section.presets();
            entries.add(new Entry(section.id(),Optional.empty(),new Bounds(0,y,g.viewport().width(),CollectionGridLayout.COLLECTION_HEADER_HEIGHT)));
            y += CollectionGridLayout.COLLECTION_HEADER_HEIGHT+2;
            if (collapsed(draft,section.id())) continue;
            for (int i=0;i<group.size();i++) {
                var p = group.get(i);
                entries.add(new Entry("gallery.card."+p.id(),Optional.of(p),AppearanceCollections.gridCard(i,g.columns(),0,y,g.cardWidth(),CARD_HEIGHT,2)));
            }
            y += ((group.size()+g.columns()-1)/g.columns())*(CARD_HEIGHT+2);
        }
        return List.copyOf(entries);
    }

    List<String> sectionIds(ClientSnapshot s, String query) {
        return sections(s,query).stream().map(Section::id).toList();
    }

    private static List<Section> sections(ClientSnapshot s, String query) {
        return sections(s,s.account(),s.activePresetId(),query);
    }

    private static List<Section> sections(ClientSnapshot s, Optional<AccountState> account, Optional<UUID> active, String query) {
        return groups(ordered(account, query).stream().filter(p -> !s.hideIncompatibleGalleryLooks()
                || active.filter(p.id()::equals).isPresent()
                || s.compatibilityFor(p).status()!=SkinCompatibilityStatus.INCOMPATIBLE).toList());
    }

    private static List<Section> groups(List<AppearancePreset> presets) {
        var sections = new ArrayList<Section>();
        for (boolean favorite : List.of(true,false)) {
            var group = presets.stream().filter(p -> p.favorite()==favorite).toList();
            if (!group.isEmpty()) sections.add(new Section(favorite ? "gallery.favorites" : "gallery.your_appearances",group));
        }
        return List.copyOf(sections);
    }

    private static int contentHeight(List<Entry> entries) { return entries.stream().mapToInt(e -> e.bounds().bottom()).max().orElse(0); }
    private static String normalizeAnchor(List<Entry> entries, String anchor) {
        if (anchor!=null && entries.stream().anyMatch(e -> e.id().equals(anchor))) return anchor;
        return entries.stream().filter(e -> e.preset().isPresent()).map(Entry::id).findFirst()
                .orElseGet(() -> entries.stream().map(Entry::id).findFirst().orElse("gallery.add"));
    }
    public String normalizeSelectedCardId(ClientSnapshot s,String query,String anchor) {
        return normalizeAnchor(positioned(s,query,defaultDraft(s),geometry(854,480)),
                anchor == null ? s.activePresetId().map(id -> "gallery.card."+id).orElse(null) : anchor);
    }
    public String normalizeSelectedCardId(ClientSnapshot s, String query, String anchor, DraftInput draft) {
        var entries=positioned(s,query,draft,geometry(854,480));
        if(anchor!=null && entries.stream().anyMatch(e->e.id().equals(anchor))) return anchor;
        if(anchor!=null && anchor.startsWith("gallery.card.")) {
            var hidden=s.account().stream().flatMap(a->a.presets().stream()).filter(p->anchor.equals("gallery.card."+p.id())).findFirst();
            if(hidden.isPresent()) {
                String section=hidden.orElseThrow().favorite() ? "gallery.favorites" : "gallery.your_appearances";
                if(entries.stream().anyMatch(e->e.id().equals(section)) && collapsed(draft,section)) return section;
            }
        }
        return normalizeAnchor(entries,anchor);
    }

    public List<String> cardIds(ClientSnapshot s, String query, DraftInput draft) {
        return cardIds(s,s.account(),s.activePresetId(),query,draft);
    }

    List<String> cardIds(ClientSnapshot s,Optional<AccountState> account,Optional<UUID> active,String query,DraftInput draft) {
        return positioned(sections(s,account,active,query),draft,geometry(854,480)).stream()
                .filter(e->e.preset().isPresent()).map(Entry::id).toList();
    }

    public List<String> cardIds(ClientSnapshot s,String query) {
        return cardIds(s,query,defaultDraft(s));
    }

    public int matchingPresetCount(ClientSnapshot s,String query) { return ordered(s.account(),query).size(); }
    public int maximumScroll(ClientSnapshot s,int w,int h,String query) { return maximumScroll(s,w,h,query,defaultDraft(s)); }
    public int maximumScroll(ClientSnapshot s,int w,int h,String query,DraftInput draft) {
        Geometry g=geometry(w,h);return Math.max(0,contentHeight(positioned(s,query,draft,g))-g.viewport().height()+2);
    }
    double initialScrollPosition(ClientSnapshot s,Optional<AccountState> account,Optional<UUID> active,String query,int w,int h,DraftInput draft) {
        Geometry g=geometry(w,h);
        if (active.isEmpty()) return 0;
        var entries=positioned(sections(s,account,active,query),draft,g);
        return entries.stream().filter(e -> e.preset().map(AppearancePreset::id).equals(active))
                .mapToInt(e -> Math.max(0,e.bounds().bottom()+2-g.viewport().height())).findFirst().orElse(0);
    }
    public int offsetFromScrollbar(ClientSnapshot s,int w,int h,String query,double thumbTop) {
        return (int)Math.round(positionFromScrollbar(s,w,h,query,thumbTop));
    }
    public double positionFromScrollbar(ClientSnapshot s,int w,int h,String query,double thumbTop) {
        return positionFromScrollbar(s,w,h,query,thumbTop,defaultDraft(s));
    }
    double positionFromScrollbar(ClientSnapshot s,int w,int h,String query,double thumbTop,DraftInput draft) {
        ViewSpec view=present(s,w,h,0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,query,Optional.empty(),0,null,draft);
        var bar=view.scrollbar().orElseThrow();int travel=bar.track().height()-bar.thumb().height();
        return travel<=0 ? 0 : Math.max(0,Math.min(1,(thumbTop-bar.track().y())/travel))*bar.maximum();
    }
    private static Geometry geometry(int w,int h) {
        if(w<=0 || h<=0) throw new IllegalArgumentException("view dimensions must be positive");
        int oldLeft=Math.min(Math.max(64,(h-73)/4)+6,Math.max(70,w-60));
        int left=Math.min((int)Math.round(oldLeft*4.0/3),Math.max(70,w-60));
        Bounds viewport=new Bounds(left+8,61,Math.max(1,w-left-16),Math.max(1,h-94));
        int columns = Math.max(1, (viewport.width() - 2) / (CARD_WIDTH + 2));
        int cardWidth = Math.max(CARD_WIDTH, Math.min(64, (viewport.width() - 4 - (columns - 1) * 2) / columns));
        return new Geometry(viewport, columns, cardWidth, left);
    }
    private static SkinVariant variantFor(AccountState a,AppearancePreset p,SkinVariant fallback) {
        return p.skin().optionalAssetId().flatMap(id -> a.skinAssets().stream().filter(asset -> asset.id().equals(id)).findFirst())
                .map(SkinAsset::variant).orElse(fallback);
    }
    private record Geometry(Bounds viewport,int columns,int cardWidth,int leftWidth) {}
    private record Section(String id,List<AppearancePreset> presets) {}
    private record Entry(String id,Optional<AppearancePreset> preset,Bounds bounds) {}
    private record RecoveryWidget(String widgetId,Bounds bounds) {}
    private static Optional<RecoveryWidget> addGlobalWidgets(
            ClientSnapshot snapshot, int width, int height, List<ViewSpec.Widget> widgets) {
        int doneWidth = Math.min(200, Math.max(1, width - 32));
        widgets.add(ViewSpec.Widget.button(
                "gallery.done",
                new Bounds((width - doneWidth) / 2, ViewChromeMetrics.STANDARD.footerRowTop(height,20), doneWidth, 20),
                UiMessage.info("gui.done"),
                !snapshot.busy()));
        ClientSnapshot.GallerySessionPresentation sessionPresentation =
                snapshot.gallerySessionPresentation();
        boolean retryVisible = sessionPresentation
                == ClientSnapshot.GallerySessionPresentation.OFFLINE_RETRY
                || (sessionPresentation == ClientSnapshot.GallerySessionPresentation.CONNECTING
                        && snapshot.sessionActivity()
                                == ClientSnapshot.SessionActivity.RECONNECTING);
        if (retryVisible) {
            Bounds bounds = recoveryBounds(width);
            widgets.add(ViewSpec.Widget.button(
                    "gallery.retry_session",
                    bounds,
                    UiMessage.info("nclskins.session.retry"),
                    rateLimitHint(snapshot, true),
                    !snapshot.busy()
                            && snapshot.sessionActivity()
                                    != ClientSnapshot.SessionActivity.RECONNECTING
                            && !snapshot.syncInProgress()
                            && snapshot.account().isPresent()));
            return Optional.of(new RecoveryWidget("gallery.retry_session", bounds));
        }
        return Optional.empty();
    }

    private static Optional<UiMessage> rateLimitHint(ClientSnapshot snapshot, boolean owner) {
        return owner && snapshot.rateLimitProgress().isPresent()
                ? Optional.of(UiMessage.info("nclskins.rate_limit.delayed"))
                : owner && snapshot.syncStatus() == AppearanceSyncStatus.PARTIAL
                        ? Optional.of(UiMessage.info("nclskins.recovery.retry_cape")) : Optional.empty();
    }

    private static List<ViewSpec.ProgressDecoration> rateLimitProgressDecorations(
            ClientSnapshot snapshot, List<ViewSpec.Widget> widgets) {
        if (snapshot.rateLimitProgress().isEmpty()) {
            return List.of();
        }
        double fraction = snapshot.rateLimitProgress().orElseThrow().fraction();
        List<ViewSpec.ProgressDecoration> decorations = new ArrayList<>();
        for (ViewSpec.Widget widget : widgets) {
            boolean retry = widget.id().equals("gallery.retry_session")
                    || widget.id().equals("gallery.retry_cape");
            if (!retry && !widget.id().equals("gallery.apply")) {
                continue;
            }
            decorations.add(new ViewSpec.ProgressDecoration(
                    widget.id() + ".rate_limit",
                    widget.id(),
                    fraction,
                    RATE_LIMIT_PROGRESS_COLOR,
                    RATE_LIMIT_PROGRESS_HEIGHT));
        }
        return List.copyOf(decorations);
    }

    private static Bounds recoveryBounds(int width) {
        int buttonWidth = Math.min(RECOVERY_BUTTON_WIDTH, Math.max(1, width - 16));
        return new Bounds(Math.max(8, width - buttonWidth - 32), 6, buttonWidth, 20);
    }

    private static void addHeader(
            ClientSnapshot snapshot,
            int width,
            Optional<RecoveryWidget> recovery,
            List<ViewSpec.Text> texts) {
        ClientSnapshot.GallerySessionPresentation sessionPresentation =
                snapshot.gallerySessionPresentation();
        boolean connecting = sessionPresentation
                == ClientSnapshot.GallerySessionPresentation.CONNECTING;
        boolean showSessionState = sessionPresentation
                != ClientSnapshot.GallerySessionPresentation.HIDDEN;
        int leftOccupied = showSessionState
                ? Math.min(SESSION_STATE_TEXT_WIDTH + 12, Math.max(0, width - 1))
                : 0;
        int rightStart = recovery.map(value -> Math.max(0, value.bounds().x() - 4)).orElse(width - 32);
        int symmetricInset = Math.max(leftOccupied, width - rightStart);
        int titleLeft;
        int titleRight;
        if (width - symmetricInset * 2 >= 1) {
            titleLeft = symmetricInset;
            titleRight = width - symmetricInset;
        } else {
            titleLeft = Math.min(leftOccupied, Math.max(0, width - 1));
            titleRight = Math.max(titleLeft + 1, rightStart);
        }
        texts.add(new ViewSpec.Text(
                "gallery.title",
                new Bounds(titleLeft, 12, Math.max(1, titleRight - titleLeft), 10),
                UiMessage.info("nclskins.gallery.title"),
                ViewSpec.Text.Alignment.CENTER));
        if (showSessionState) {
            texts.add(new ViewSpec.Text(
                    "gallery.offline",
                    new Bounds(
                            8,
                            12,
                            Math.min(SESSION_STATE_TEXT_WIDTH, Math.max(1, width - 16)),
                            10),
                    UiMessage.info(connecting
                            ? "nclskins.session.connecting"
                            : "nclskins.session.offline"),
                    ViewSpec.Text.Alignment.LEFT));
        }
    }

    private static ViewSpec coldLoadingView(int width, int height) {
        int contentTop = 33;
        int contentBottom = Math.max(contentTop + 10, height - FOOTER_HEIGHT);
        int loadingY = contentTop + Math.max(0, (contentBottom - contentTop - 10) / 2);
        return new ViewSpec(
                "gallery",
                UiMessage.info("nclskins.gallery.title"),
                width,
                height,
                List.of(
                        new ViewSpec.Panel(
                                "header",
                                new Bounds(0, 0, width, 33),
                                ViewSpec.Panel.Style.VANILLA_HEADER),
                        new ViewSpec.Panel(
                                "footer",
                                new Bounds(
                                        0,
                                        Math.max(0, height - FOOTER_HEIGHT),
                                        width,
                                        FOOTER_HEIGHT),
                                ViewSpec.Panel.Style.VANILLA_FOOTER)),
                List.of(
                        new ViewSpec.Text(
                                "gallery.title",
                                new Bounds(0, 12, width, 10),
                                UiMessage.info("nclskins.gallery.title"),
                                ViewSpec.Text.Alignment.CENTER),
                        new ViewSpec.Text(
                                "gallery.loading",
                                new Bounds(8, loadingY, Math.max(1, width - 16), 10),
                                UiMessage.info("nclskins.status.loading"),
                                ViewSpec.Text.Alignment.CENTER)),
                List.of(),
                List.of(),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

}
