package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.client.ExpectedAppearance;
import com.naocraftlab.skins.client.PlayerAppearanceSink.ApplyResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AppearanceOverrideControllerTest {
    private static final UUID PROFILE_ID = UUID.fromString("30f7c0d5-1603-4b8f-b1de-74804ca4aef5");

    @Test
    void replacingAnOverrideAttachesTheReplacementBeforeReleasingThePreviousHandles() {
        List<String> events = new ArrayList<>();
        AppearanceOverrideController<FakeOverride, String> controller = controller(events);
        FakeOverride first = override("first", "skin-1", "cape-1");
        FakeOverride second = override("second", "skin-2", "cape-2");

        assertEquals(ApplyResult.UPDATED, controller.install(first));
        events.clear();

        assertEquals(ApplyResult.UPDATED, controller.install(second));

        assertEquals(List.of("attach:second", "release:skin-1", "release:cape-1"), events);
        assertSame(second, controller.active().orElseThrow());
    }

    @Test
    void matchingApplyAndReattachReuseTheInstalledOverride() {
        List<String> events = new ArrayList<>();
        AppearanceOverrideController<FakeOverride, String> controller = controller(events);
        FakeOverride installed = override("current", "skin", null);
        controller.install(installed);
        events.clear();

        assertEquals(
                ApplyResult.UPDATED,
                controller.reattachIfActive(installed.expected()).orElseThrow());
        assertEquals(ApplyResult.UPDATED, controller.reattach(installed.expected()));

        assertEquals(List.of("attach:current", "attach:current"), events);
        assertSame(installed, controller.active().orElseThrow());
    }

    @Test
    void invalidationKeepsMatchingOverrideButRestoresAndReleasesStaleOverride() {
        List<String> events = new ArrayList<>();
        AppearanceOverrideController<FakeOverride, String> controller = controller(events);
        FakeOverride installed = override("current", "skin", "cape");
        controller.install(installed);
        events.clear();

        controller.invalidate(installed.expected());
        assertEquals(List.of("attach:current"), events);
        assertTrue(controller.active().isPresent());

        events.clear();
        controller.invalidate(new ExpectedAppearance(
                UUID.fromString("c21ec9f5-aa70-489e-8462-1f57aef0c74c"),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty()));
        assertEquals(List.of("restore:" + PROFILE_ID, "release:skin", "release:cape"), events);
        assertFalse(controller.active().isPresent());
    }

    @Test
    void failedAttachmentDoesNotTakeOwnershipFromTheNativeCreator() {
        List<String> events = new ArrayList<>();
        AppearanceOverrideController<FakeOverride, String> controller =
                new AppearanceOverrideController<>(new AppearanceOverrideController.Strategy<>() {
                    @Override
                    public ExpectedAppearance expected(FakeOverride override) {
                        return override.expected();
                    }

                    @Override
                    public List<String> handles(FakeOverride override) {
                        return override.handles();
                    }

                    @Override
                    public ApplyResult attach(FakeOverride override) {
                        events.add("attach:" + override.name());
                        if (override.name().equals("broken")) {
                            throw new IllegalStateException("unavailable player info");
                        }
                        return ApplyResult.UPDATED;
                    }

                    @Override
                    public void restore() {
                        events.add("restore:" + PROFILE_ID);
                    }

                    @Override
                    public void release(String handle) {
                        events.add("release:" + handle);
                    }
                });
        FakeOverride current = override("current", "skin-1", null);
        controller.install(current);
        events.clear();

        try {
            controller.install(override("broken", "skin-2", "cape-2"));
        } catch (IllegalStateException expected) {
            assertEquals("unavailable player info", expected.getMessage());
        }

        assertEquals(List.of("attach:broken"), events);
        assertSame(current, controller.active().orElseThrow());
    }

    @Test
    void replacementRemainsActiveWhenReleasingAPreviousHandleFails() {
        List<String> events = new ArrayList<>();
        AppearanceOverrideController<FakeOverride, String> controller =
                new AppearanceOverrideController<>(new AppearanceOverrideController.Strategy<>() {
                    @Override
                    public ExpectedAppearance expected(FakeOverride override) {
                        return override.expected();
                    }

                    @Override
                    public List<String> handles(FakeOverride override) {
                        return override.handles();
                    }

                    @Override
                    public ApplyResult attach(FakeOverride override) {
                        events.add("attach:" + override.name());
                        return ApplyResult.UPDATED;
                    }

                    @Override
                    public void restore() {}

                    @Override
                    public void release(String handle) {
                        events.add("release:" + handle);
                        if ("broken".equals(handle)) {
                            throw new IllegalStateException("native release failed");
                        }
                    }
                });
        controller.install(override("previous", "broken", "remaining"));
        FakeOverride replacement = override("replacement", "new-skin", null);
        events.clear();

        assertEquals(ApplyResult.UPDATED, controller.install(replacement));

        assertEquals(
                List.of("attach:replacement", "release:broken", "release:remaining"),
                events);
        assertSame(replacement, controller.active().orElseThrow());
    }

    @Test
    void closeRestoresAndReleasesExactlyOnce() {
        List<String> events = new ArrayList<>();
        AppearanceOverrideController<FakeOverride, String> controller = controller(events);
        controller.install(override("current", "skin", "cape"));
        events.clear();

        controller.close();
        controller.close();

        assertEquals(List.of("restore:" + PROFILE_ID, "release:skin", "release:cape"), events);
        assertFalse(controller.active().isPresent());
    }

    @Test
    void accountDefaultClearImmediatelyRestoresAndReleasesDesiredOverride() {
        List<String> events = new ArrayList<>();
        AppearanceOverrideController<FakeOverride, String> controller = controller(events);
        controller.install(override("current", "skin", "cape"));
        events.clear();

        controller.clear();

        assertEquals(List.of("restore:" + PROFILE_ID, "release:skin", "release:cape"), events);
        assertTrue(controller.active().isEmpty());
    }

    @Test
    void deferredReplacementRemainsActiveAndCanAttachWhenPlayerBecomesReady() {
        List<String> events = new ArrayList<>();
        boolean[] playerReady = {false};
        AppearanceOverrideController<FakeOverride, String> controller =
                new AppearanceOverrideController<>(new AppearanceOverrideController.Strategy<>() {
                    @Override
                    public ExpectedAppearance expected(FakeOverride override) {
                        return override.expected();
                    }

                    @Override
                    public List<String> handles(FakeOverride override) {
                        return override.handles();
                    }

                    @Override
                    public ApplyResult attach(FakeOverride override) {
                        events.add("attach:" + override.name());
                        return playerReady[0] ? ApplyResult.UPDATED : ApplyResult.DEFERRED;
                    }

                    @Override
                    public void restore() {
                        events.add("restore:" + PROFILE_ID);
                    }

                    @Override
                    public void release(String handle) {
                        events.add("release:" + handle);
                    }
                });
        FakeOverride desired = override("desired", "skin", "cape");

        assertEquals(ApplyResult.DEFERRED, controller.install(desired));
        assertSame(desired, controller.active().orElseThrow());

        playerReady[0] = true;
        assertEquals(ApplyResult.UPDATED, controller.reattach(desired.expected()));
        assertEquals(List.of("attach:desired", "attach:desired"), events);
        assertSame(desired, controller.active().orElseThrow());
    }

    private static AppearanceOverrideController<FakeOverride, String> controller(List<String> events) {
        return new AppearanceOverrideController<>(new AppearanceOverrideController.Strategy<>() {
            @Override
            public ExpectedAppearance expected(FakeOverride override) {
                return override.expected();
            }

            @Override
            public List<String> handles(FakeOverride override) {
                return override.handles();
            }

            @Override
            public ApplyResult attach(FakeOverride override) {
                events.add("attach:" + override.name());
                return ApplyResult.UPDATED;
            }

            @Override
            public void restore() {
                events.add("restore:" + PROFILE_ID);
            }

            @Override
            public void release(String handle) {
                events.add("release:" + handle);
            }
        });
    }

    private static FakeOverride override(String name, String skin, String cape) {
        List<String> handles = cape == null ? List.of(skin) : List.of(skin, cape);
        return new FakeOverride(name, emptyAppearance(), handles);
    }

    private static ExpectedAppearance emptyAppearance() {
        return new ExpectedAppearance(
                PROFILE_ID,
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty());
    }

    private record FakeOverride(
            String name, ExpectedAppearance expected, List<String> handles) {}
}
