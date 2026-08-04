package com.naocraftlab.skins.compat.mc262;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.EntityType;

public final class Minecraft26Api {
    private static final Method GUI_SCREEN = methodOrNull("net.minecraft.client.gui.Gui", "screen");
    private static final Method GUI_SET_SCREEN = methodOrNull(
            "net.minecraft.client.gui.Gui", "setScreen", Screen.class);
    private static final Field GUI = fieldOrNull(Minecraft.class, "gui");
    private static final Field LEGACY_SCREEN = fieldOrNull(Minecraft.class, "screen");
    private static final Method LEGACY_SET_SCREEN = methodOrNull(Minecraft.class, "setScreen", Screen.class);
    private static final Method THREE_ARGUMENT_TAB_SELECTION = methodOrNull(
            TabManager.class, "setCurrentTab", Tab.class, boolean.class, boolean.class);
    private static final Method MENU_TAB_BUILDER = methodOrNull(
            "net.minecraft.client.gui.components.tabs.MenuTabBar",
            "builder",
            TabManager.class,
            int.class);
    private static final Method LEGACY_TAB_BUILDER = methodOrNull(
            TabNavigationBar.class, "builder", TabManager.class, int.class);

    private Minecraft26Api() {}

    static Screen currentScreen(Minecraft minecraft) {
        if (GUI_SCREEN == null || GUI == null) {
            return (Screen) get(LEGACY_SCREEN, minecraft);
        }
        return (Screen) invoke(GUI_SCREEN, get(GUI, minecraft));
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        if (GUI_SET_SCREEN == null || GUI == null) {
            invoke(LEGACY_SET_SCREEN, minecraft, screen);
            return;
        }
        invoke(GUI_SET_SCREEN, get(GUI, minecraft), screen);
    }

    static TabNavigationBar buildTabBar(TabManager manager, int width, Iterable<Tab> tabs) {
        Object builder = invoke(
                MENU_TAB_BUILDER == null ? LEGACY_TAB_BUILDER : MENU_TAB_BUILDER,
                null,
                manager,
                width);
        Method addTab = methodOrNull(builder.getClass(), "addTab", Tab.class);
        if (addTab == null) {
            List<Tab> collected = new ArrayList<>();
            tabs.forEach(collected::add);
            invoke(
                    method(builder.getClass(), "addTabs", Tab[].class),
                    builder,
                    (Object) collected.toArray(Tab[]::new));
        } else {
            for (Tab tab : tabs) {
                invoke(addTab, builder, tab);
            }
        }
        return (TabNavigationBar) invoke(method(builder.getClass(), "build"), builder);
    }

    static void arrange(TabNavigationBar bar, int width) {
        Method withWidth = methodOrNull(bar.getClass(), "arrangeElements", int.class);
        invoke(withWidth == null ? method(bar.getClass(), "arrangeElements") : withWidth,
                bar,
                withWidth == null ? new Object[0] : new Object[] {width});
    }

    static void select(TabManager manager, Tab tab) {
        if (THREE_ARGUMENT_TAB_SELECTION == null) {
            manager.setCurrentTab(tab, false);
        } else {
            invoke(THREE_ARGUMENT_TAB_SELECTION, manager, tab, false, false);
        }
    }

    @SuppressWarnings("unchecked")
    static EntityType<?> mannequin() {
        Field modern = fieldOrNull("net.minecraft.world.entity.EntityTypes", "MANNEQUIN");
        Field legacy = fieldOrNull(EntityType.class, "MANNEQUIN");
        return (EntityType<?>) get(modern == null ? legacy : modern, null);
    }

    private static Method methodOrNull(String owner, String name, Class<?>... parameters) {
        try {
            return method(Class.forName(owner), name, parameters);
        } catch (ClassNotFoundException | IllegalStateException ignored) {
            return null;
        }
    }

    private static Method methodOrNull(Class<?> owner, String name, Class<?>... parameters) {
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Field fieldOrNull(String owner, String name) {
        try {
            return fieldOrNull(Class.forName(owner), name);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static Field fieldOrNull(Class<?> owner, String name) {
        try {
            return owner.getField(name);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static Object get(Field field, Object receiver) {
        if (field == null) {
            throw new IllegalStateException("Missing 26.x API field");
        }
        try {
            return field.get(receiver);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot access 26.x API field " + field, error);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException("Missing 26.x API method " + owner.getName() + "." + name, error);
        }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (IllegalAccessException | InvocationTargetException error) {
            throw new IllegalStateException("Cannot invoke 26.x API method " + method, error);
        }
    }
}
