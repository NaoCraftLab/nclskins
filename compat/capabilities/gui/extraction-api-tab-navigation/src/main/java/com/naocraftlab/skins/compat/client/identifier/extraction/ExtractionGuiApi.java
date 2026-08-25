package com.naocraftlab.skins.compat.client.identifier.extraction;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.EntityType;

public final class ExtractionGuiApi {
    private ExtractionGuiApi() {}

    static Screen currentScreen(Minecraft minecraft) {
        return minecraft.screen;
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.setScreen(screen);
    }

    static TabNavigationBar buildTabBar(TabManager manager, int width, Iterable<Tab> tabs) {
        List<Tab> collected = new ArrayList<>();
        tabs.forEach(collected::add);
        return TabNavigationBar.builder(manager, width)
                .addTabs(collected.toArray(Tab[]::new))
                .build();
    }

    static void arrange(TabNavigationBar bar, int width) {
        bar.arrangeElements();
    }

    static void select(TabManager manager, Tab tab) {
        manager.setCurrentTab(tab, false);
    }

    static EntityType<?> mannequin() {
        return EntityType.MANNEQUIN;
    }
}
