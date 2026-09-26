package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import javax.tools.ToolProvider
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

final class ExtractionInputAdapterTest {
    @TempDir Path temporary
    private final File repository = new File('../..').canonicalFile

    @Test
    void nativeFamiliesTranslateKeysAndPointersWithoutChangingTheirOrderingPolicy() {
        ['glfw', 'input-constants'].each { String family ->
            File root = temporary.resolve(family).toFile()
            boolean logical = family == 'input-constants'
            Map<String, String> fixtures = [
                    'net/minecraft/client/input/KeyEvent.java': '''package net.minecraft.client.input;
public record KeyEvent(int key, int shortcutKey, boolean hasShiftDown) {}''',
                    'net/minecraft/client/input/MouseButtonEvent.java': '''package net.minecraft.client.input;
public record MouseButtonEvent(int button) {}''',
                    'com/naocraftlab/skins/runtime/ViewSpec.java': '''package com.naocraftlab.skins.runtime;
public class ViewSpec {
 public enum NavigationCommand { TAB_FORWARD, TAB_BACKWARD, LEFT, RIGHT, UP, DOWN, ACTIVATE }
}''',
                    'org/lwjgl/glfw/GLFW.java': '''package org.lwjgl.glfw;
public class GLFW {
 public static final int GLFW_KEY_TAB=258, GLFW_KEY_LEFT=263, GLFW_KEY_RIGHT=262,
 GLFW_KEY_UP=265, GLFW_KEY_DOWN=264, GLFW_KEY_ENTER=257, GLFW_KEY_KP_ENTER=335, GLFW_KEY_SPACE=32;
}''',
                    'com/mojang/blaze3d/platform/InputConstants.java': '''package com.mojang.blaze3d.platform;
public class InputConstants {
 public static final int KEYCODE_TAB=9, KEYCODE_LEFT=1001, KEYCODE_RIGHT=1002,
 KEYCODE_UP=1003, KEYCODE_DOWN=1004, KEYCODE_RETURN=13, KEYCODE_NUMPADENTER=1005,
 KEYCODE_SPACE=32, MOUSE_BUTTON_LEFT=1;
}'''
            ]
            fixtures.each { String path, String text ->
                File file = new File(root, path)
                file.parentFile.mkdirs()
                file.text = text
            }
            File source = new File(repository, "compat/capabilities/gui/extraction-screen-${family}/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/ExtractionInputAdapter.java")
            List<String> arguments = ['-d', root.absolutePath]
            arguments.addAll(fixtures.keySet().collect { new File(root, it).absolutePath })
            arguments.add(source.absolutePath)
            assertEquals(0, ToolProvider.systemJavaCompiler.run(null, null, null, arguments as String[]))
            new URLClassLoader([root.toURI().toURL()] as URL[], (ClassLoader) null).withCloseable { loader ->
                Class adapter = loader.loadClass('com.naocraftlab.skins.compat.client.identifier.extraction.ExtractionInputAdapter')
                Class key = loader.loadClass('net.minecraft.client.input.KeyEvent')
                Class mouse = loader.loadClass('net.minecraft.client.input.MouseButtonEvent')
                def invoke = { String name, Class parameter, Object value ->
                    def method = adapter.getDeclaredMethod(name, parameter)
                    method.accessible = true
                    method.invoke(null, value)
                }
                def event = { int code, boolean shift -> key.getConstructor(Integer.TYPE, Integer.TYPE, Boolean.TYPE)
                        .newInstance(logical ? -91 : code, logical ? code : -91, shift) }
                Map<Integer, String> keys = logical
                        ? [9: 'TAB_FORWARD', 1001: 'LEFT', 1002: 'RIGHT', 1003: 'UP', 1004: 'DOWN', 13: 'ACTIVATE', 1005: 'ACTIVATE', 32: 'ACTIVATE']
                        : [258: 'TAB_FORWARD', 263: 'LEFT', 262: 'RIGHT', 265: 'UP', 264: 'DOWN', 257: 'ACTIVATE', 335: 'ACTIVATE', 32: 'ACTIVATE']
                keys.each { int code, String command ->
                    Optional result = invoke('navigationCommand', key, event(code, false)) as Optional
                    assertEquals(command, result.orElseThrow().toString())
                }
                assertEquals('TAB_BACKWARD', (invoke('navigationCommand', key, event(logical ? 9 : 258, true)) as Optional).orElseThrow().toString())
                assertEquals(Optional.empty(), invoke('navigationCommand', key, event(-7, false)))
                Object wrongRepresentation = key.getConstructor(Integer.TYPE, Integer.TYPE, Boolean.TYPE)
                        .newInstance(logical ? 9 : -91, logical ? -91 : 258, false)
                assertEquals(Optional.empty(), invoke('navigationCommand', key, wrongRepresentation))
                assertTrue(invoke('isSpace', key, event(32, false)) as boolean)
                assertFalse(invoke('isEnterKey', key, event(32, false)) as boolean)
                assertTrue(invoke('isEnterKey', key, event(logical ? 13 : 257, false)) as boolean)
                assertTrue(invoke('isEnterKey', key, event(logical ? 1005 : 335, false)) as boolean)
                Object primary = mouse.getConstructor(Integer.TYPE).newInstance(logical ? 1 : 0)
                assertTrue(invoke('isPrimaryPointer', mouse, primary) as boolean)
                assertEquals(0, invoke('productPointerButton', mouse, primary))
                assertFalse(invoke('isPrimaryPointer', mouse, mouse.getConstructor(Integer.TYPE).newInstance(2)) as boolean)
                ['synchronizeBeforeRebuild', 'focusBeforeScrollPublication', 'synchronizeAfterNavigation'].each { String name ->
                    def method = adapter.getDeclaredMethod(name)
                    method.accessible = true
                    assertEquals(logical, method.invoke(null), name)
                }
            }
        }
    }
}
