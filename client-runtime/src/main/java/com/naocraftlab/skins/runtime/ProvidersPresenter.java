package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.provider.ProviderChannel;
import com.naocraftlab.skins.core.provider.ProviderSkin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ProvidersPresenter {
    public ViewSpec present(AppearanceProviders providers, AppearanceProviders.Component component,
            boolean adding, boolean busy, PreviewInteractionModel transform,
            SkinVariant defaultVariant, int width, int height) {
        return present(providers, component, adding, busy, transform, defaultVariant, width, height, null, null);
    }

    public ViewSpec present(AppearanceProviders providers, AppearanceProviders.Component component,
            boolean adding, boolean busy, PreviewInteractionModel transform,
            SkinVariant defaultVariant, int width, int height, BuiltinProvider selectedSkin, BuiltinProvider selectedCape) {
        return present(providers, component, adding, busy, transform, defaultVariant, width, height, selectedSkin, selectedCape, Optional.empty());
    }

    public ViewSpec present(AppearanceProviders providers, AppearanceProviders.Component component,
            boolean adding, boolean busy, PreviewInteractionModel transform, SkinVariant defaultVariant,
            int width, int height, BuiltinProvider selectedSkin, BuiltinProvider selectedCape,
            Optional<ClientSnapshot.RateLimitProgress> cooldown) {
        return present(providers, component, adding, busy, transform, defaultVariant,
                width, height, selectedSkin, selectedCape, cooldown, false, null);
    }

    public ViewSpec present(AppearanceProviders providers, AppearanceProviders.Component component,
            boolean adding, boolean busy, PreviewInteractionModel transform, SkinVariant defaultVariant,
            int width, int height, BuiltinProvider selectedSkin, BuiltinProvider selectedCape,
            Optional<ClientSnapshot.RateLimitProgress> cooldown, boolean linkPreparing, UiMessage linkFeedback) {
        return present(providers, component, adding, busy, transform, defaultVariant, width, height,
                selectedSkin, selectedCape, cooldown, linkPreparing, linkFeedback, 0,
                message -> message.key());
    }

    public ViewSpec present(AppearanceProviders providers, AppearanceProviders.Component component,
            boolean adding, boolean busy, PreviewInteractionModel transform, SkinVariant defaultVariant,
            int width, int height, BuiltinProvider selectedSkin, BuiltinProvider selectedCape,
            Optional<ClientSnapshot.RateLimitProgress> cooldown, boolean linkPreparing, UiMessage linkFeedback,
            double desiredOffset, TextResolver textResolver) {
        if (adding) return presentChooser(providers, component, busy, width, height, 0);
        BuiltinProvider selected = component == AppearanceProviders.Component.SKIN ? selectedSkin : selectedCape;
        List<BuiltinProvider> order = component == AppearanceProviders.Component.SKIN ? providers.skin().order() : providers.cape().order();
        int divider = width / 2;
        int x = divider + 8;
        int contentWidth = Math.max(1, width - x - 8);
        boolean showFeedback = linkFeedback != null && component == AppearanceProviders.Component.CAPE;
        int feedbackHeight = showFeedback ? Math.max(12, textResolver.wrappedHeight(linkFeedback, contentWidth)) : 0;
        int feedbackY = height - 37 - feedbackHeight;
        int viewportBottom = showFeedback ? feedbackY - 4 : height - 37;
        Bounds rowViewport = new Bounds(x, 65, contentWidth, Math.max(1, viewportBottom - 65));
        int rowContentHeight = Math.max(0, order.size() * 40 - 4);
        int maximum = Math.max(0, rowContentHeight - rowViewport.height());
        int offset = (int) Math.round(Math.max(0, Math.min(maximum, desiredOffset)));
        var widgets = new ArrayList<ViewSpec.Widget>();
        var texts = new ArrayList<ViewSpec.Text>();
        var icons = new ArrayList<ViewSpec.IconDecoration>();
        var panels = List.of(
                new ViewSpec.Panel("header", new Bounds(0, 0, width, 33), ViewSpec.Panel.Style.VANILLA_HEADER),
                new ViewSpec.Panel("footer", new Bounds(0, height - 33, width, 33), ViewSpec.Panel.Style.VANILLA_FOOTER),
                new ViewSpec.Panel("providers.content", new Bounds(divider, 33, width - divider, Math.max(1, height - 66)), ViewSpec.Panel.Style.VANILLA_TAB_CONTENT));
        boolean overlay = transform.outerLayerVisibility().visible(OuterLayerPart.HEAD);
        for (var tab : AppearanceProviders.Component.values()) {
            int index = tab == AppearanceProviders.Component.SKIN ? 0 : 1;
            String id = "providers.tab." + tab.name();
            Bounds bounds = VerticalTabStyle.bounds(divider, index);
            widgets.add(ViewSpec.Widget.verticalTabButton(id, bounds,
                    UiMessage.info(index == 0 ? "nclskins.providers.skins" : "nclskins.providers.capes"),
                    noValueIcon(tab), tab == component, true));
            Object value = index == 0 ? providers.skin().resolve().map(ProviderChannel.Resolved::value).orElse(null)
                    : providers.cape().resolve().map(ProviderChannel.Resolved::value).orElse(null);
            icons.add(icon(id, new Bounds(VerticalTabStyle.iconX(bounds.x(), tab == component) + 4, bounds.y() + 4, 16, 16), value, overlay, tab));
        }
        widgets.add(ViewSpec.Widget.iconButton("providers.add", new Bounds(x, 39, 20, 20),
                UiMessage.info("nclskins.providers.add"), GuiIcon.ACTION_ADD_PROVIDER, !busy));
        texts.add(new ViewSpec.Text("providers.component", new Bounds(x + 24, 44, Math.max(1, contentWidth - 48), 10),
                UiMessage.info(component == AppearanceProviders.Component.SKIN ? "nclskins.providers.skins" : "nclskins.providers.capes"), ViewSpec.Text.Alignment.CENTER));
        widgets.add(ViewSpec.Widget.iconButton("providers.refresh", new Bounds(x + contentWidth - 20, 39, 20, 20),
                UiMessage.info("nclskins.providers.refresh"), GuiIcon.ACTION_REFRESH, !busy));
        List<BuiltinProvider> displayed = order;
        int y = 65 - offset;
        for (BuiltinProvider provider : displayed) {
            UiMessage name = providerName(provider);
            String id = "providers.row." + provider.name();
            widgets.add(ViewSpec.Widget.selectableCard(id, new Bounds(x, y, contentWidth, 36), name, provider == selected, !busy));
            Object value = component == AppearanceProviders.Component.SKIN
                    ? providers.skin().observation(provider).value()
                    : providers.cape().observation(provider).value();
            icons.add(icon(id, new Bounds(x + 2, y + 2, 32, 32), value, overlay, component));
            texts.add(new ViewSpec.Text(id + ".name", new Bounds(x + 38, y + 4, Math.max(1, contentWidth - (providers.galleryAvailable() ? 90 : 66)), 10), name, ViewSpec.Text.Alignment.LEFT));
            for (int action = 0; action < 2; action++) {
                String verb = action == 0 ? "up" : "down";
                widgets.add(new ViewSpec.Widget("providers." + verb + "." + provider.name(), ViewSpec.WidgetKind.PROVIDER_ACTION,
                        new Bounds(x + 18, y + 2 + action * 16, 16, 16),
                        UiMessage.info(action == 0 ? "nclskins.providers.up" : "nclskins.providers.down"), Optional.empty(), Optional.empty(),
                        !busy && (action == 0 ? order.indexOf(provider) > 0 : order.indexOf(provider) < order.size() - 1), true, 0));
            }
            if (provider == BuiltinProvider.OPTIFINE && component == AppearanceProviders.Component.CAPE) {
                widgets.add(ViewSpec.Widget.iconButton("providers.account." + provider.name(),
                        new Bounds(x + contentWidth - 48, y + 8, 20, 20),
                        UiMessage.info("nclskins.providers.open_account"), GuiIcon.ACTION_OPEN_ACCOUNT,
                        !busy && !linkPreparing));
            } else if (provider.writable() && providers.galleryAvailable()) widgets.add(ViewSpec.Widget.iconButton("providers.edit." + provider.name(),
                    new Bounds(x + contentWidth - 48, y + 8, 20, 20), UiMessage.info("nclskins.providers.edit"), GuiIcon.ACTION_EDIT, !busy));
            widgets.add(ViewSpec.Widget.iconButton("providers.remove." + provider.name(),
                    new Bounds(x + contentWidth - 24, y + 8, 20, 20), UiMessage.info("nclskins.providers.remove"), GuiIcon.ACTION_REMOVE, !busy));
            y += 40;
        }
        if (showFeedback) {
            texts.add(new ViewSpec.Text("providers.account.feedback",
                    new Bounds(x, feedbackY, contentWidth, feedbackHeight),
                    linkFeedback, ViewSpec.Text.Alignment.CENTER, ViewSpec.Text.Layout.WRAP));
        }
        widgets.add(ViewSpec.Widget.button("providers.back", new Bounds((width - backWidth(width)) / 2, height - 28, backWidth(width), 20), UiMessage.info("gui.back"), true));
        var skin = selectedSkin == null ? providers.skin().resolve().map(ProviderChannel.Resolved::value) : Optional.ofNullable(providers.skin().observation(selectedSkin).value());
        var cape = selectedCape == null ? providers.cape().resolve().map(ProviderChannel.Resolved::value) : Optional.ofNullable(providers.cape().observation(selectedCape).value());
        if (cape.isPresent()) widgets.add(ViewSpec.Widget.iconOnlyButton("providers.preview_mode", new Bounds(2, 35, 20, 20),
                UiMessage.info(transform.capeMode() == PreviewRenderer.CapeMode.ELYTRA ? "item.minecraft.elytra" : "options.modelPart.cape"),
                transform.capeMode() == PreviewRenderer.CapeMode.ELYTRA ? GuiIcon.APPEARANCE_BACK_ELYTRA : GuiIcon.APPEARANCE_BACK_CAPE, true));
        texts.add(new ViewSpec.Text("providers.title", new Bounds(0, 12, width, 10), UiMessage.info(adding ? "nclskins.providers.add" : "nclskins.providers.title"), ViewSpec.Text.Alignment.CENTER));
        String revision = skin.map(s -> "provider:skin:" + s.sha256()).orElse("provider:default");
        SkinReference reference = skin.map(s -> SkinReference.asset(UUID.nameUUIDFromBytes(s.sha256().getBytes(StandardCharsets.US_ASCII)))).orElse(SkinReference.accountDefault());
        var preview = new ViewSpec.Preview("editor.preview", new Bounds(0, 0, width, height), new Bounds(0, 0, divider, height), reference,
                revision, skin.map(ProviderSkin::variant).orElse(defaultVariant), cape.map(c -> c.textureCacheKey() == null ? c.id() : "provider:cape:" + c.textureCacheKey()),
                cape.isPresent() ? transform.capeMode() : PreviewRenderer.CapeMode.OFF, transform.outerLayerVisibility(), transform.yawDegrees(), transform.pitchDegrees(), transform.scale(), Optional.empty(), Optional.empty(), PreviewRenderer.PreviewIntent.EDITOR_DRAFT).withCapeElytra(cape.map(value -> !Boolean.FALSE.equals(value.hasElytra())).orElse(true));
        var navigation = new ArrayList<ViewSpec.NavigationNode>();
        int tabOrder = 0;
        for (var widget : widgets) {
            if (widget.kind() == ViewSpec.WidgetKind.TAB_BUTTON) {
                navigation.add(new ViewSpec.NavigationNode(widget.id(), widget.bounds(), Optional.of("providers.tabs"), navigation.size(),
                        widget.value().filter("selected"::equals).isPresent() ? tabOrder++ : -1, widget.enabled(), ViewSpec.NavigationPattern.VERTICAL_LIST, Optional.of(widget.id())));
            } else if (widget.id().matches("providers\\.(row|up|down|edit|remove|account)\\..+")) {
                String provider = widget.id().substring(widget.id().lastIndexOf('.') + 1);
                Bounds row = widgets.stream().filter(w -> w.id().equals("providers.row." + provider)).findFirst().orElseThrow().bounds();
                navigation.add(new ViewSpec.NavigationNode(widget.id(), row, Optional.of("providers.rows"), navigation.size(),
                        tabOrder++, widget.enabled(), ViewSpec.NavigationPattern.COMPOSITE_LIST, Optional.of(widget.id())));
            } else {
                navigation.add(ViewSpec.NavigationNode.control(widget, navigation.size(), tabOrder++));
            }
        }
        var tabs = new ViewSpec.TabGroup("providers.tabs", new Bounds(VerticalTabStyle.bounds(divider, 0).x(), 45, 24, 48), List.of(
                new ViewSpec.Tab("providers.tab.SKIN", UiMessage.info("nclskins.providers.skins"), component == AppearanceProviders.Component.SKIN, true),
                new ViewSpec.Tab("providers.tab.CAPE", UiMessage.info("nclskins.providers.capes"), component == AppearanceProviders.Component.CAPE, true)), ViewSpec.TabOrientation.VERTICAL);
        List<ViewSpec.ProgressDecoration> progress = order.contains(BuiltinProvider.MINECRAFT)
                ? cooldown.map(value -> List.of(new ViewSpec.ProgressDecoration("providers.minecraft.cooldown",
                        "providers.row.MINECRAFT", value.fraction(), 0xFF5A8FCB, 2, 33, 2))).orElse(List.of()) : List.of();
        Optional<ViewSpec.Scrollbar> scrollbar = Optional.empty();
        if (maximum > 0) {
            Bounds track = new Bounds(width - 6, rowViewport.y(), 6, rowViewport.height());
            int thumbHeight = Math.min(track.height(), Math.max(8, track.height() * track.height()
                    / (track.height() + maximum)));
            scrollbar = Optional.of(new ViewSpec.Scrollbar(track,
                    new Bounds(track.x(), track.y() + offset * (track.height() - thumbHeight) / maximum,
                            6, thumbHeight), offset, maximum, ViewSpec.Scrollbar.Orientation.VERTICAL));
        }
        return new ViewSpec("providers", UiMessage.info("nclskins.providers.title"), width, height,
                panels, texts, widgets, List.of(preview), scrollbar, List.of(tabs), Optional.empty(),
                List.of(new ViewSpec.ClipRegion("providers.rows", rowViewport,
                        List.of("providers.row.", "providers.up.", "providers.down.",
                                "providers.edit.", "providers.remove.", "providers.account.OPTIFINE"))),
                List.of(), icons, List.of(new ViewSpec.ScrollSurface("providers.rows", rowViewport,
                        ViewSpec.Scrollbar.Orientation.VERTICAL, offset, maximum)), List.of(), progress, navigation);
    }

    public ViewSpec presentChooser(AppearanceProviders providers, AppearanceProviders.Component component,
            boolean busy, int width, int height, double desiredOffset) {
        var order = component == AppearanceProviders.Component.SKIN ? providers.skin().order() : providers.cape().order();
        UiMessage title = UiMessage.info(component == AppearanceProviders.Component.SKIN
                ? "nclskins.providers.add_skin" : "nclskins.providers.add_cape");
        int contentWidth = Math.min(320, Math.max(1, width - 32));
        int x = (width - contentWidth) / 2;
        Bounds viewport = new Bounds(0, 33, width, Math.max(1, height - 66));
        long available = java.util.Arrays.stream(BuiltinProvider.values())
                .filter(provider -> component == AppearanceProviders.Component.SKIN
                        ? provider.supportsSkin() : provider.supportsCape()).count();
        int maximum = Math.max(0, 9 + (int) available * 24 - viewport.height());
        int offset = (int) Math.round(Math.max(0, Math.min(maximum, desiredOffset)));
        var widgets = new ArrayList<ViewSpec.Widget>();
        var nodes = new ArrayList<ViewSpec.NavigationNode>();
        int y = 42 - offset;
        for (var provider : BuiltinProvider.values()) {
            if (component == AppearanceProviders.Component.SKIN && !provider.supportsSkin()
                    || component == AppearanceProviders.Component.CAPE && !provider.supportsCape()) continue;
            var widget = ViewSpec.Widget.button("providers.row." + provider.name(), new Bounds(x, y, contentWidth, 20),
                    providerName(provider), !busy && !order.contains(provider));
            widgets.add(widget);
            nodes.add(new ViewSpec.NavigationNode(widget.id(), widget.bounds(), Optional.of("providers.chooser"),
                    nodes.size(), nodes.size(), widget.enabled(), ViewSpec.NavigationPattern.VERTICAL_LIST, Optional.of(widget.id())));
            y += 24;
        }
        var back = ViewSpec.Widget.button("providers.back", new Bounds((width - backWidth(width)) / 2,
                height - 28, backWidth(width), 20), UiMessage.info("gui.back"), !busy);
        widgets.add(back);
        nodes.add(ViewSpec.NavigationNode.control(back, nodes.size(), nodes.size()));
        Optional<ViewSpec.Scrollbar> scrollbar = Optional.empty();
        if (maximum > 0) {
            Bounds track = new Bounds(width - 6, viewport.y(), 6, viewport.height());
            int thumbHeight = Math.min(track.height(), Math.max(8, track.height() * track.height() / (track.height() + maximum)));
            scrollbar = Optional.of(new ViewSpec.Scrollbar(track,
                    new Bounds(track.x(), track.y() + offset * (track.height() - thumbHeight) / maximum, 6, thumbHeight),
                    offset, maximum, ViewSpec.Scrollbar.Orientation.VERTICAL));
        }
        return new ViewSpec("provider_chooser", title, width, height,
                List.of(new ViewSpec.Panel("header", new Bounds(0, 0, width, 33), ViewSpec.Panel.Style.VANILLA_HEADER),
                        new ViewSpec.Panel("footer", new Bounds(0, height - 33, width, 33), ViewSpec.Panel.Style.VANILLA_FOOTER)),
                List.of(new ViewSpec.Text("providers.title", new Bounds(8, 12, Math.max(1, width - 16), 10), title, ViewSpec.Text.Alignment.CENTER)),
                widgets, List.of(), scrollbar, List.of(), Optional.empty(),
                List.of(new ViewSpec.ClipRegion("providers.chooser", viewport, List.of("providers.row."))),
                List.of(), List.of(), List.of(new ViewSpec.ScrollSurface("providers.chooser", viewport,
                        ViewSpec.Scrollbar.Orientation.VERTICAL, offset, maximum))).withNavigationNodes(nodes);
    }

    private static int backWidth(int width) {
        return Math.min(200, Math.max(1, width - 32));
    }

    private static UiMessage providerName(BuiltinProvider provider) {
        return switch (provider) {
            case OFFLINE -> UiMessage.info("nclskins.providers.offline");
            case MINECRAFT -> UiMessage.literal("Minecraft", UiMessage.Severity.INFO);
            case OPTIFINE -> UiMessage.info("nclskins.providers.optifine");
        };
    }

    private static GuiIcon noValueIcon(AppearanceProviders.Component component) {
        return switch (component) {
            case SKIN -> GuiIcon.PROVIDER_SKIN_NO_VALUE;
            case CAPE -> GuiIcon.PROVIDER_CAPE_NO_VALUE;
        };
    }

    private ViewSpec.IconDecoration icon(String owner, Bounds bounds, Object value, boolean overlay, AppearanceProviders.Component component) {
        Optional<ViewSpec.ProviderTexture> texture = value instanceof ProviderSkin skin ? Optional.of(new ViewSpec.ProviderTexture(skin.sha256(), true, overlay))
                : value instanceof ProviderCape cape && cape.textureCacheKey() != null ? Optional.of(new ViewSpec.ProviderTexture(cape.textureCacheKey(), false, false)) : Optional.empty();
        return new ViewSpec.IconDecoration(owner + ".icon", bounds, noValueIcon(component), owner, 1, 1, texture);
    }
}
