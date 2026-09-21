package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import javax.tools.ToolProvider
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows

final class ScreenKeybindingsTest {
    @TempDir Path temporary
    private final File repository = new File('../..').canonicalFile

    @Test
    void nativeDispatchSupportsBothCategoryApisWithoutFancyMenu() {
        ['primitive-input', 'event-input'].each { String leaf ->
            File root = temporary.resolve(leaf).toFile()
            Map<String, String> fixtures = [
                'com/mojang/blaze3d/platform/InputConstants.java': '''package com.mojang.blaze3d.platform;
public class InputConstants {
 public static final Key UNKNOWN = new Key(-1);
 public record Key(int getValue) { public String getName() { return "key:" + getValue; } }
 public enum Type { KEYSYM, MOUSE }
}''',
                'net/minecraft/resources/Identifier.java': '''package net.minecraft.resources;
public record Identifier(String namespace, String path) {
 public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace,path); }
}''',
                'net/minecraft/client/KeyMapping.java': '''package net.minecraft.client;
import com.mojang.blaze3d.platform.InputConstants;
public class KeyMapping implements Comparable<KeyMapping> {
 public record Category(net.minecraft.resources.Identifier id) {}
 public final String name;
 public InputConstants.Key key = InputConstants.UNKNOWN;
 public int clicks;
 public KeyMapping(String name, int value, Object category) { this.name = name; }
 public boolean isUnbound() { return key.getValue() == -1; }
 public String saveString() { return key.getName(); }
 public boolean consumeClick() { if (clicks == 0) return false; clicks--; return true; }
 public int compareTo(KeyMapping other) { return other.name.compareTo(name); }
}''',
                'net/minecraft/client/multiplayer/ClientLevel.java': 'package net.minecraft.client.multiplayer; public class ClientLevel {}',
                'net/minecraft/client/Minecraft.java': '''package net.minecraft.client;
public class Minecraft {
 private static final Minecraft INSTANCE = new Minecraft();
 public net.minecraft.client.multiplayer.ClientLevel level = new net.minecraft.client.multiplayer.ClientLevel();
 public static Minecraft getInstance() { return INSTANCE; }
 public Window getWindow() { return new Window(); }
 public static class Window { public long getWindow() { return 17; } public long handle() { return 17; } }
}''',
                'com/naocraftlab/skins/compat/loader/MinecraftClientHookAdapter.java': '''package com.naocraftlab.skins.compat.loader;
import com.naocraftlab.skins.client.ScreenDestination;
public class MinecraftClientHookAdapter {
 private static final MinecraftClientHookAdapter INSTANCE = new MinecraftClientHookAdapter();
 public boolean active = true;
 public final java.util.List<ScreenDestination> opened = new java.util.ArrayList<>();
 public static MinecraftClientHookAdapter instance() { return INSTANCE; }
 public boolean keybindingContextActive() { return active; }
 public void openDestination(ScreenDestination destination) { opened.add(destination); active = false; }
}''',
                'Probe.java': '''import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.multiplayer.ClientLevel;
import com.mojang.blaze3d.platform.InputConstants.Key;
import com.naocraftlab.skins.client.ScreenDestination;
import com.naocraftlab.skins.compat.keybindings.ScreenKeybindings;
import com.naocraftlab.skins.compat.loader.MinecraftClientHookAdapter;
public class Probe {
 static void require(boolean value) { if (!value) throw new AssertionError(); }
 public static void run() {
  List<KeyMapping> mappings = new ArrayList<>();
  ScreenKeybindings.register(mappings::add);
  require(mappings.size() == 5 && mappings.stream().allMatch(KeyMapping::isUnbound));
  List<KeyMapping> sorted = new ArrayList<>(mappings);
  Collections.reverse(sorted); Collections.sort(sorted); require(sorted.equals(mappings));
  var client = Minecraft.getInstance(); var host = MinecraftClientHookAdapter.instance();
  Key button = new Key(4);
  for (KeyMapping mapping : mappings) { mapping.key = button; mapping.clicks = 3; }
  ScreenKeybindings.press(17, button, 1); ScreenKeybindings.tick(client);
  require(host.opened.equals(List.of(ScreenDestination.GALLERY)));
  require(mappings.stream().allMatch(m -> m.clicks == 0));
  host.active = true; ScreenKeybindings.tick(client); require(host.opened.size() == 1);
  for (int action : new int[] {0, 2}) ScreenKeybindings.press(17, button, action);
  ScreenKeybindings.press(18, button, 1); ScreenKeybindings.tick(client); require(host.opened.size() == 1);
  host.active = false; ScreenKeybindings.press(17, button, 1);
  host.active = true; ScreenKeybindings.tick(client); require(host.opened.size() == 1);
  ScreenKeybindings.press(17, button, 1); host.active = false; ScreenKeybindings.tick(client);
  host.active = true; ScreenKeybindings.tick(client); require(host.opened.size() == 1);
  ScreenKeybindings.press(17, button, 1); client.level = new ClientLevel();
  ScreenKeybindings.tick(client); require(host.opened.size() == 1);
  for (int i = 0; i < mappings.size(); i++) mappings.get(i).key = new Key(100 + i);
  for (int i = 0; i < mappings.size(); i++) {
   host.active = true; ScreenKeybindings.press(17, new Key(100+i), 1); ScreenKeybindings.tick(client);
   require(host.opened.get(host.opened.size()-1) == ScreenDestination.values()[i]);
  }
  int count = host.opened.size(); host.active = true;
  ScreenKeybindings.matcher(key -> List.of());
  ScreenKeybindings.press(17, new Key(100), 1); ScreenKeybindings.tick(client);
  require(host.opened.size() == count);
  KeyMapping foreign = new KeyMapping("foreign", -1, "foreign");
  ScreenKeybindings.matcher(key -> List.of(foreign));
  ScreenKeybindings.press(17, new Key(100), 1); ScreenKeybindings.tick(client);
  require(host.opened.size() == count);
  ScreenKeybindings.matcher(key -> List.of(mappings.get(4)));
  ScreenKeybindings.press(17, new Key(100), 1); ScreenKeybindings.tick(client);
  require(host.opened.get(host.opened.size()-1) == ScreenDestination.SKIN_IMPORT);
 }
}'''
            ]
            List<String> sources = []
            fixtures.each { String name, String source ->
                File file = new File(root, name)
                file.parentFile.mkdirs()
                file.text = source
                sources.add(file.path)
            }
            ['client-contract/src/main/java/com/naocraftlab/skins/client/ScreenDestination.java',
             'client-contract/src/main/java/com/naocraftlab/skins/client/ScreenKeybinding.java',
             'client-runtime/src/main/java/com/naocraftlab/skins/runtime/ScreenKeybindingDispatcher.java',
             'compat/keybindings/shared/src/main/java/com/naocraftlab/skins/compat/keybindings/ScreenKeybindings.java',
             "compat/capabilities/keybindings/${leaf}/src/main/java/com/naocraftlab/skins/compat/keybindings/ScreenKeyMapping.java"].each {
                sources.add(new File(repository, it.toString()).path)
            }
            assertEquals(0, ToolProvider.systemJavaCompiler.run(null, null, null,
                    (['--release', '17', '-d', root.path] + sources) as String[]))
            new URLClassLoader([root.toURI().toURL()] as URL[], ClassLoader.platformClassLoader).withCloseable { loader ->
                assertThrows(ClassNotFoundException) { loader.loadClass('de.keksuccino.fancymenu.customization.action.Action') }
                loader.loadClass('Probe').getMethod('run').invoke(null)
            }
        }
    }
}
