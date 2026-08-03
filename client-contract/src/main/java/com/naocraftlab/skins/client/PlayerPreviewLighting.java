package com.naocraftlab.skins.client;

public final class PlayerPreviewLighting {
    private static final Rig CENTERED_FRONT = new Rig(
            Direction.normalized(0.2F, -1.0F, 1.0F),
            Direction.normalized(-0.2F, -1.0F, 0.0F));

    private PlayerPreviewLighting() {
    }

    public static Rig centeredFront() {
        return CENTERED_FRONT;
    }

    public record Rig(Direction primary, Direction fill) {
        public Rig {
            if (primary == null || fill == null) {
                throw new IllegalArgumentException("Player preview lights must not be null");
            }
        }
    }

    public record Direction(float x, float y, float z) {
        private static Direction normalized(float x, float y, float z) {
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (!Float.isFinite(length) || length <= 0.0F) {
                throw new IllegalArgumentException("Player preview light must be finite and non-zero");
            }
            return new Direction(x / length, y / length, z / length);
        }
    }
}
