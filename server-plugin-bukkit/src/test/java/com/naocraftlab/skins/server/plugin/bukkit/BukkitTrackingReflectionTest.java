package com.naocraftlab.skins.server.plugin.bukkit;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;


final class BukkitTrackingReflectionTest {
    @Test
    void resolvesMoonriseTrackedEntityDirectlyFromActorHandle() throws ReflectiveOperationException {
        Object tracked = new Object();

        assertSame(tracked, BukkitTrackingReflection.directTrackedEntity(
                new MoonriseEntity(tracked)).orElseThrow());
    }

    @Test
    void resolvesLegacyFoliaTrackedEntityFromActorField() throws ReflectiveOperationException {
        EntityTracker tracked = new EntityTracker();

        assertSame(tracked, BukkitTrackingReflection.directTrackedEntity(
                new LegacyFoliaEntity(tracked)).orElseThrow());
    }

    @Test
    void selectsLegacyTrackingOverloadByPlayerDescriptor() throws ReflectiveOperationException {
        Method remove = BukkitTrackingReflection.playerMethod(
                LegacyTrackedEntity.class, LegacyPlayer.class, "a");
        Method update = BukkitTrackingReflection.playerMethod(
                LegacyTrackedEntity.class, LegacyPlayer.class, "b");

        assertEquals(LegacyPlayer.class, remove.getParameterTypes()[0]);
        assertEquals(LegacyPlayer.class, update.getParameterTypes()[0]);
    }

    private static final class MoonriseEntity {
        private final Object tracked;

        private MoonriseEntity(Object tracked) {
            this.tracked = tracked;
        }

        public Object moonrise$getTrackedEntity() {
            return tracked;
        }
    }

    private static final class LegacyPlayer {
    }

    private static final class EntityTracker {
    }

    private static class LegacyFoliaEntityBase {
        @SuppressWarnings("unused")
        private final EntityTracker tracker;

        private LegacyFoliaEntityBase(EntityTracker tracker) {
            this.tracker = tracker;
        }
    }

    private static final class LegacyFoliaEntity extends LegacyFoliaEntityBase {
        private LegacyFoliaEntity(EntityTracker tracker) {
            super(tracker);
        }
    }

    @SuppressWarnings("unused")
    private static final class LegacyTrackedEntity {
        public void a(Object packet) {
        }

        public void a(LegacyPlayer player) {
        }

        public void b(Object packet) {
        }

        public void b(LegacyPlayer player) {
        }
    }
}
