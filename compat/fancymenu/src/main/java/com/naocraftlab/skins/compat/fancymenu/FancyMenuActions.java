package com.naocraftlab.skins.compat.fancymenu;

import com.naocraftlab.skins.client.ScreenDestination;
import de.keksuccino.fancymenu.customization.action.Action;
import de.keksuccino.fancymenu.customization.action.ActionRegistry;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;

final class FancyMenuActions extends Action {
    private final ScreenDestination destination;
    private final String name;
    private final String description;
    private final Consumer<ScreenDestination> open;

    private FancyMenuActions(String id, ScreenDestination destination, String name, String description,
            Consumer<ScreenDestination> open) {
        super(id);
        this.destination = destination;
        this.name = name;
        this.description = description;
        this.open = open;
    }

    static void register(Consumer<ScreenDestination> open) throws ReflectiveOperationException {
        if (Modifier.isFinal(Action.class.getModifiers())) throw new NoSuchMethodException();
        Action.class.getConstructor(String.class);
        for (var method : Action.class.getMethods()) {
            if (Modifier.isAbstract(method.getModifiers())) {
                var implementation = FancyMenuActions.class.getMethod(method.getName(), method.getParameterTypes());
                if (Modifier.isAbstract(implementation.getModifiers())
                        || implementation.getReturnType() != method.getReturnType()) throw new NoSuchMethodException();
            }
        }
        requireMethod(Action.class, "canRunAsync", boolean.class, false);
        requireMethod(ActionRegistry.class, "register", void.class, true, Action.class);
        requireMethod(ActionRegistry.class, "getAction", Action.class, true, String.class);
        List<FancyMenuActions> actions = List.of(
                new FancyMenuActions("nclskins_open_gallery", ScreenDestination.GALLERY,
                        "nclskins.integration.fancymenu.gallery.name", "nclskins.integration.fancymenu.gallery.description", open),
                new FancyMenuActions("nclskins_edit_active_preset", ScreenDestination.ACTIVE_EDITOR,
                        "nclskins.integration.fancymenu.editor.name", "nclskins.integration.fancymenu.editor.description", open),
                new FancyMenuActions("nclskins_open_providers", ScreenDestination.PROVIDERS,
                        "nclskins.integration.fancymenu.providers.name", "nclskins.integration.fancymenu.providers.description", open),
                new FancyMenuActions("nclskins_open_skin_catalog", ScreenDestination.SKIN_CATALOG,
                        "nclskins.integration.fancymenu.catalog.name", "nclskins.integration.fancymenu.catalog.description", open),
                new FancyMenuActions("nclskins_open_skin_import", ScreenDestination.SKIN_IMPORT,
                        "nclskins.integration.fancymenu.import.name", "nclskins.integration.fancymenu.import.description", open));
        for (FancyMenuActions action : actions) {
            if (ActionRegistry.getAction(action.getIdentifier()) != null) throw new NoSuchMethodException();
        }
        actions.forEach(ActionRegistry::register);
    }

    private static void requireMethod(Class<?> owner, String name, Class<?> returns, boolean isStatic,
            Class<?>... arguments) throws NoSuchMethodException {
        var method = owner.getMethod(name, arguments);
        if (method.getReturnType() != returns || Modifier.isStatic(method.getModifiers()) != isStatic
                || (!isStatic && Modifier.isFinal(method.getModifiers()))) throw new NoSuchMethodException();
    }

    @Override public boolean hasValue() { return false; }
    @Override public boolean canRunAsync() { return false; }
    @Override public void execute(String value) { open.accept(destination); }
    @Override public Component getDisplayName() { return Component.translatable(name); }
    @Override public Component getDescription() { return Component.translatable(description); }
    @Override public Component getValueDisplayName() { return null; }
    @Override public String getValuePreset() { return null; }
}
