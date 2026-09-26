package com.naocraftlab.skins.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class AppearanceCollections {
    record Section(String id, int itemCount, boolean collapsed, int cardHeight) {}

    record SectionLayout(Section section, int headerTop, int cardsTop, int columns) {
        Bounds header(int x, int top, int width) {
            return new Bounds(x, top + headerTop, width, CollectionGridLayout.COLLECTION_HEADER_HEIGHT);
        }

        Bounds card(int index, int x, int top, int width) {
            return gridCard(index, columns, x, top + cardsTop, width, section.cardHeight(), CatalogCardSizing.GAP);
        }
    }

    record Layout(List<SectionLayout> sections, int contentHeight) {
        Layout {
            sections = List.copyOf(sections);
        }
    }

    record Header(Optional<ViewSpec.Widget> widget, ViewSpec.NavigationNode navigation) {}

    static Layout layout(List<Section> sections, int columns) {
        var positioned = new ArrayList<SectionLayout>();
        int y = 0;
        for (Section section : sections) {
            int cardsTop = y + CollectionGridLayout.COLLECTION_HEADER_HEIGHT + CollectionGridLayout.COLLECTION_HEADER_GAP;
            positioned.add(new SectionLayout(section, y, cardsTop, columns));
            y += CollectionGridLayout.sectionHeight(section.itemCount(), section.collapsed(), columns,
                    section.cardHeight(), CollectionGridLayout.COLLECTION_HEADER_HEIGHT, CatalogCardSizing.GAP);
        }
        return new Layout(positioned, y);
    }

    static List<CollectionGridLayout.Section> gridSections(List<Section> sections) {
        return sections.stream().map(section -> new CollectionGridLayout.Section(
                section.itemCount(), section.collapsed(), section.cardHeight())).toList();
    }

    static Header header(String id, Bounds bounds, UiMessage label, boolean enabled, Bounds viewport,
                         String surface, int documentOrder, int tabOrder) {
        return new Header(visibleVertically(bounds, viewport)
                ? Optional.of(ViewSpec.Widget.collectionHeader(id, bounds, label, enabled, false)) : Optional.empty(),
                navigation(id, bounds, surface, documentOrder, tabOrder, enabled,
                        ViewSpec.NavigationPattern.GRID, Optional.of(id)));
    }

    static ViewSpec.NavigationNode navigation(String id, Bounds bounds, String surface, int documentOrder,
                                              int tabOrder, boolean enabled, ViewSpec.NavigationPattern pattern,
                                              Optional<String> action) {
        return ViewSpec.NavigationNode.card(id, bounds, surface, documentOrder, tabOrder, enabled, pattern, action);
    }

    static Bounds gridCard(int index, int columns, int x, int top, int width, int height, int gap) {
        return new Bounds(x + index % columns * (width + gap), top + index / columns * (height + gap), width, height);
    }

    static boolean visibleVertically(Bounds bounds, Bounds viewport) {
        return bounds.bottom() > viewport.y() && bounds.y() < viewport.bottom();
    }

    static boolean intersects(Bounds bounds, Bounds viewport) {
        return bounds.right() > viewport.x() && bounds.x() < viewport.right() && visibleVertically(bounds, viewport);
    }

    static ViewSpec.ClipRegion clip(String id, Bounds viewport, List<String> prefixes) {
        return new ViewSpec.ClipRegion(id, viewport, prefixes);
    }

    private AppearanceCollections() {}
}
