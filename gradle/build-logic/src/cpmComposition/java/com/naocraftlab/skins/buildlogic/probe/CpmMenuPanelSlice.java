package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.config.MenuPreviewPlacement;
import com.naocraftlab.skins.runtime.*;
import java.util.*;
import java.lang.reflect.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

final class CpmMenuPanelSlice implements AutoCloseable {
    private final Class<?> panel;
    private final Class<?> graphicsType;
    private final boolean immediate;
    private final MenuScreen screen;
    private int pointerX;
    private int pointerY;
    private final ClientRuntime runtime;
    private final MenuNativePassFixture fixture;
    private static ClientRuntime active;
    static int warmups;

    CpmMenuPanelSlice(ClassLoader parent, String namespace, PreviewRenderer.PreviewAppearance appearance) throws Exception {
        this(parent,namespace,appearance,CpmBakedDisplayProbe.Graphics.class,CpmBakedDisplayProbe.Client.class);
    }

    CpmMenuPanelSlice(ClassLoader parent, String namespace, PreviewRenderer.PreviewAppearance appearance, Class<?> graphicsType, Class<?> clientType) throws Exception {
        screen = Boolean.getBoolean("nclskins.menuPause") ? new Pause() : new Title();
        this.graphicsType = graphicsType;
        immediate = namespace.contains("resourcelocation");
        fixture = new MenuNativePassFixture(appearance);
        runtime = fixture.runtime;
        active = runtime;
        var names = new HashMap<String,String>();
        names.put("net/minecraft/client/gui/screens/Screen", internal(MenuScreen.class));
        names.put("net/minecraft/client/gui/screens/TitleScreen", internal(Title.class));
        names.put("net/minecraft/client/gui/screens/PauseScreen", internal(Pause.class));
        names.put("net/minecraft/client/gui/components/events/GuiEventListener", internal(Listener.class));
        names.put("net/minecraft/client/gui/components/AbstractWidget", internal(Widget.class));
        names.put("net/minecraft/client/gui/components/AbstractButton", internal(Widget.class));
        names.put("net/minecraft/client/gui/components/Renderable", internal(Renderable.class));
        names.put("net/minecraft/client/gui/narration/NarrationElementOutput", internal(Narration.class));
        names.put("net/minecraft/client/input/InputWithModifiers", internal(Input.class));
        names.put("net/minecraft/client/input/MouseButtonEvent", internal(Input.class));
        names.put("net/minecraft/network/chat/Component", internal(Text.class));
        names.put("net/minecraft/network/chat/MutableComponent", internal(Text.class));
        names.put("net/minecraft/client/Minecraft", internal(clientType));
        names.put("net/minecraft/client/gui/GuiGraphics", internal(graphicsType));
        names.put("net/minecraft/client/gui/GuiGraphicsExtractor", internal(graphicsType));
        names.put(namespace.replace('.','/') + "NclSkinsExtractionClientRuntime",internal(Boundary.class));
        names.put(namespace.replace('.','/') + "SubmissionClientRuntime",internal(Boundary.class));
        names.put(namespace.replace('.','/') + "NclSkinsScreen",internal(GalleryTerminal.class));
        names.put(namespace.replace('.','/') + "ExtractionGuiApi",internal(Gui.class));
        names.put(namespace.replace('.','/') + "mixin/ScreenRenderablesAccessor",internal(Renderables.class));
        names.put(namespace.replace('.','/') + "ImmediateClientRuntime",internal(ImmediateBoundary.class));
        names.put("com/mojang/blaze3d/systems/RenderSystem",internal(Device.class));
        String owner = namespace + (immediate ? "NclSkinsMenuPreview" : "NclSkinsMenuPanel");
        String extractionAdapter = namespace + "GuiExtraction";
        String support = "com.naocraftlab.skins.compat.gui.immediate.MenuScreenSupport";
        var loader = new ClassLoader(parent) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(owner) && !name.startsWith(owner + "$")
                        && !(namespace.endsWith(".extraction.") && (name.equals(extractionAdapter) || name.startsWith(extractionAdapter + "$")))
                        && !(immediate && (name.equals(support) || name.startsWith(support + "$")))) return super.loadClass(name,resolve);
                var type = findLoadedClass(name);
                if (type == null) {
                    try (var input = getParent().getResourceAsStream(name.replace('.','/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        var original = input.readAllBytes();
                        var writer = new ClassWriter(0);
                        var node = new org.objectweb.asm.tree.ClassNode(Opcodes.ASM9);
                        new ClassReader(original).accept(node, 0);
                        if (name.equals(extractionAdapter)) CpmPreviewScreenSlice.sliceGuiAdapter(node, Set.of("isVisible", "setVisible"));
                        node.accept(new ClassRemapper(writer,new Remapper(Opcodes.ASM9) {
                            @Override public String map(String value) { return names.getOrDefault(value,value); }
                        }));
                        var bytes = writer.toByteArray();
                        System.out.println("Menu caller class " + name + " original=" + digest(original) + " terminalBound=" + digest(bytes));
                        type = defineClass(name,bytes,0,bytes.length);
                    } catch (java.io.IOException failure) { throw new ClassNotFoundException(name,failure); }
                }
                if (resolve) resolveClass(type);
                return type;
            }
        };
        panel = loader.loadClass(owner);
        screen.children.add(new Widget(327,142,200,20,Text.translatable("fixture")));
        install(MenuPreviewPlacement.RIGHT);
    }

    Object renderer() throws Exception {
        Object underlay = screen.renderables.get(0);
        if (panel.getName().contains("playerinfo")) {
            var stateField = underlay.getClass().getDeclaredField("previewState"); stateField.setAccessible(true);
            Object state = stateField.get(underlay);
            var ready = state.getClass().getDeclaredMethod("rendererWhenReady"); ready.setAccessible(true);
            return ready.invoke(state);
        }
        var field = underlay.getClass().getDeclaredField("renderer"); field.setAccessible(true);
        return field.get(underlay);
    }
    void appearance(PreviewRenderer.PreviewAppearance appearance) {
        fixture.appearance(appearance);
    }
    void animate(com.naocraftlab.skins.core.png.CapeTimeline timeline, List<TextureRegistry.TextureHandle> frames) {
        fixture.animate(timeline, frames);
    }
    void animate(com.naocraftlab.skins.core.png.CapeTimeline timeline, List<TextureRegistry.TextureHandle> frames, boolean glint) {
        fixture.animate(timeline, frames, glint);
    }
    void animationTime(long elapsedMillis) { fixture.animationTime(elapsedMillis); }
    void replaceCapeAsset(TextureRegistry.TextureHandle handle) { fixture.replaceCapeAsset(handle); }
    com.naocraftlab.skins.client.CapeAssetOwner capeOwner() {
        return runtime.menuPreviewAppearance().orElseThrow().capeAssetOwner();
    }
    void install(MenuPreviewPlacement placement) throws Exception {
        if (immediate) panel.getMethod("install",MenuScreen.class,Runnable.class,java.util.function.Consumer.class,MenuPreviewPlacement.class)
                .invoke(null,screen,(Runnable) () -> { throw new AssertionError("Unadmitted menu navigation"); },(java.util.function.Consumer<Widget>)screen.children::add,placement);
        else panel.getMethod("install",MenuScreen.class,java.util.function.Consumer.class,MenuPreviewPlacement.class)
                .invoke(null,screen,(java.util.function.Consumer<Widget>)screen.children::add,placement);
    }
    void draw(Object graphics) throws Exception {
        Object underlay = screen.renderables.get(0);
        boolean extraction = panel.getName().contains(".extraction.");
        var method = extraction ? extractionCallback(underlay.getClass(), graphicsType)
                : underlay.getClass().getDeclaredMethod("render", graphicsType, int.class, int.class, float.class);
        method.setAccessible(true);
        try {
            if (method.getParameterCount() == 1) {
                var extractor = (CpmOwnedPreviewDispatchProbe.Graphics) graphics;
                extractor.pointerX = pointerX;
                extractor.pointerY = pointerY;
                method.invoke(underlay, graphics);
            } else method.invoke(underlay, graphics, pointerX, pointerY, 0F);
        }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }
    static java.lang.reflect.Method extractionCallback(Class<?> underlay, Class<?> graphics) {
        var methods = Arrays.stream(underlay.getSuperclass().getDeclaredMethods())
                .filter(method -> method.getName().equals("extractRenderState") && method.getReturnType() == void.class)
                .filter(method -> Arrays.equals(method.getParameterTypes(), new Class<?>[]{graphics})
                        || Arrays.equals(method.getParameterTypes(), new Class<?>[]{graphics, int.class, int.class, float.class}))
                .toList();
        if (methods.size() != 1) throw new AssertionError("Expected exact native menu extraction callback: " + underlay);
        return methods.get(0);
    }
    void dimensions(int width,int height) { screen.width=width; screen.height=height; }
    void pointer(int x,int y) { pointerX=x; pointerY=y; }
    void anchor(boolean visible) { screen.children.get(0).visible=visible; }
    boolean actionVisible() { return screen.children.get(screen.children.size()-1).visible; }
    @Override public void close() throws Exception {
        panel.getMethod("removed",MenuScreen.class).invoke(null,screen);
        fixture.close(); active=null;
    }
    private static String internal(Class<?> type) { return type.getName().replace('.','/'); }
    private static String digest(byte[] bytes) { try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception e) { throw new AssertionError(e); } }

    public interface Listener {}
    public interface Renderable {}
    public interface Renderables { List<Renderable> nclskins$renderables(); }
    public interface Narration {}
    public interface Input {}
    public static class MenuScreen implements Renderables {
        public int width=854,height=480;
        final List<Widget> children=new ArrayList<>();
        final List<Renderable> renderables=new ArrayList<>();
        public List<Widget> children() { return children; }
        public List<Renderable> nclskins$renderables() { return renderables; }
    }
    public static final class Title extends MenuScreen {}
    public static final class Pause extends MenuScreen {}
    public static class Widget implements Listener {
        public boolean active=true,visible=true;
        public boolean isVisible() { return visible; }
        public void setVisible(boolean value) { visible = value; }
        protected int x,y,width,height;
        private boolean focused;
        public Widget(int x,int y,int width,int height,Text text) { this.x=x;this.y=y;this.width=width;this.height=height; }
        public int getX(){return x;} public int getY(){return y;} public int getWidth(){return width;} public int getHeight(){return height;}
        public void setX(int x){this.x=x;} public void setY(int y){this.y=y;} public void setWidth(int width){this.width=width;} public void setHeight(int height){this.height=height;}
        public int getRight(){return x+width;} public int getBottom(){return y+height;}
        public boolean isFocused(){return focused;} public void setFocused(boolean value){focused=value;}
        public void defaultButtonNarrationText(Narration output) {}
    }
    public interface Text { static Text translatable(String key){return new TextValue();} }
    public static final class TextValue implements Text {}
    public static final class Device { public static void clear(int mask,boolean osx) {} }
    public static final class ImmediateBoundary {
        private static final ImmediateBoundary INSTANCE = new ImmediateBoundary();
        public static ImmediateBoundary instance(){return INSTANCE;}
        public boolean menuPreviewAvailable(){return active!=null;}
        public ClientRuntime runtime(){return Objects.requireNonNull(active);}
    }
    public static final class Boundary {
        public static boolean closed(){return active==null;}
        public static ClientRuntime runtime(){return Objects.requireNonNull(active);}
    }
    public static final class GalleryTerminal extends MenuScreen {
        public GalleryTerminal(MenuScreen parent) {}
        public static void warmSessionSnapshot(){warmups++;}
    }
    public static final class Gui { public static void setScreen(CpmBakedDisplayProbe.Client client,MenuScreen screen) {throw new AssertionError("Gallery navigation not in render proof");} }
}
