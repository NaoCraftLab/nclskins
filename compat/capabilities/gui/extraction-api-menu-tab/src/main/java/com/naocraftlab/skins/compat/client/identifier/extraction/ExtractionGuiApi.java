package com.naocraftlab.skins.compat.client.identifier.extraction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

public final class ExtractionGuiApi {
    private ExtractionGuiApi() {}

    static Screen currentScreen(Minecraft minecraft) {
        return minecraft.gui.screen();
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.gui.setScreen(screen);
    }

    static TabNavigationBar buildTabBar(TabManager manager, int width, Iterable<Tab> tabs) {
        MenuTabBar.Builder builder = MenuTabBar.builder(manager, width);
        tabs.forEach(builder::addTab);
        return builder.build();
    }

    static void arrange(TabNavigationBar bar, int width) {
        bar.arrangeElements(width);
    }

    static void select(TabManager manager, Tab tab) {
        manager.setCurrentTab(tab, false, false);
    }

    static EntityType<?> mannequin() {
        return EntityTypes.MANNEQUIN;
    }
}
