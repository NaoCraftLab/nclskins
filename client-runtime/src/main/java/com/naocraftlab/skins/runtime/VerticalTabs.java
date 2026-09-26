package com.naocraftlab.skins.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class VerticalTabs {
    record Content(Optional<ViewSpec.ProviderTexture> texture) {}

    record Tab(String id, UiMessage label, GuiIcon icon, boolean selected, boolean enabled,
               Optional<Content> content) {
        Tab(String id, UiMessage label, GuiIcon icon, boolean selected, boolean enabled) {
            this(id, label, icon, selected, enabled, Optional.empty());
        }
    }

    record Presentation(ViewSpec.TabGroup group, List<ViewSpec.Widget> widgets,
                        List<ViewSpec.IconDecoration> icons) {
        Presentation {
            widgets = List.copyOf(widgets);
            icons = List.copyOf(icons);
        }

        ViewSpec.NavigationNode navigation(int index, int documentOrder, int selectedTabOrder) {
            ViewSpec.Widget widget = widgets.get(index);
            return new ViewSpec.NavigationNode(widget.id(), widget.bounds(), Optional.of(group.id()),
                    documentOrder, group.tabs().get(index).selected() ? selectedTabOrder : -1,
                    widget.enabled(), ViewSpec.NavigationPattern.VERTICAL_LIST, Optional.of(widget.id()));
        }
    }

    static Presentation present(String id, int dividerX, List<Tab> tabs) {
        var widgets = new ArrayList<ViewSpec.Widget>();
        var icons = new ArrayList<ViewSpec.IconDecoration>();
        var entries = new ArrayList<ViewSpec.Tab>();
        for (int index = 0; index < tabs.size(); index++) {
            Tab tab = tabs.get(index);
            Bounds bounds = VerticalTabStyle.bounds(dividerX, index);
            widgets.add(ViewSpec.Widget.verticalTabButton(tab.id(), bounds, tab.label(), tab.icon(),
                    tab.selected(), tab.enabled()));
            entries.add(new ViewSpec.Tab(tab.id(), tab.label(), tab.selected(), tab.enabled()));
            tab.content().ifPresent(content -> icons.add(new ViewSpec.IconDecoration(tab.id() + ".icon",
                    new Bounds(VerticalTabStyle.iconX(bounds.x(), tab.selected()) + 4, bounds.y() + 4, 16, 16),
                    tab.icon(), tab.id(), 1, 1, content.texture())));
        }
        return new Presentation(new ViewSpec.TabGroup(id,
                new Bounds(VerticalTabStyle.bounds(dividerX, 0).x(), VerticalTabStyle.FIRST_TAB_TOP,
                        VerticalTabStyle.WIDTH, tabs.size() * VerticalTabStyle.HEIGHT),
                entries, ViewSpec.TabOrientation.VERTICAL), widgets, icons);
    }

    private VerticalTabs() {}
}
