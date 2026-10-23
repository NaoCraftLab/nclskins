package com.naocraftlab.skins.compat.waveycapes;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.naocraftlab.skins.client.TextureUseValidity;
import java.util.ArrayList;
import java.util.List;

final class CapeVertexReplay {
    static final int MAX_COMMANDS = 8192;
    private static final VertexConsumer DISCARD = new Forward(null, 0, null);
    private final TextureUseValidity validity;
    private final List<int[]> commands = new ArrayList<>();
    private int generation;
    private boolean recording;

    CapeVertexReplay(TextureUseValidity validity) { this.validity = validity; }

    VertexConsumer record(VertexConsumer target) {
        reset();
        recording = validity.isValid();
        return new Forward(this, generation, target);
    }

    VertexConsumer replay(VertexConsumer target) {
        try {
            if (recording && validity.isValid()) {
                for (int[] command : commands) emit(target, command);
            }
        } finally { reset(); }
        return DISCARD;
    }

    private void reset() {
        commands.clear();
        recording = false;
        generation++;
    }

    private void append(int expectedGeneration, int[] command) {
        if (generation != expectedGeneration || !recording) return;
        if (!validity.isValid() || commands.size() == MAX_COMMANDS) reset();
        else commands.add(command);
    }

    private static int bits(float value) { return Float.floatToRawIntBits(value); }
    private static float value(int bits) { return Float.intBitsToFloat(bits); }

    private static void emit(VertexConsumer target, int[] c) {
        switch (c[0]) {
            case 0 -> target.addVertex(value(c[1]), value(c[2]), value(c[3]));
            case 1 -> target.setColor(c[1], c[2], c[3], c[4]);
            case 2 -> target.setColor(c[1]);
            case 3 -> target.setUv(value(c[1]), value(c[2]));
            case 4 -> target.setUv1(c[1], c[2]);
            case 5 -> target.setUv2(c[1], c[2]);
            case 6 -> target.setNormal(value(c[1]), value(c[2]), value(c[3]));
            case 7 -> target.setLineWidth(value(c[1]));
            default -> throw new IllegalArgumentException("Unknown cape vertex command");
        }
    }

    private static final class Forward implements VertexConsumer {
        private final CapeVertexReplay stream;
        private final int generation;
        private final VertexConsumer target;

        private Forward(CapeVertexReplay stream, int generation, VertexConsumer target) {
            this.stream = stream;
            this.generation = generation;
            this.target = target;
        }

        private VertexConsumer send(int... command) {
            if (target == null) return this;
            try {
                stream.append(generation, command);
                emit(target, command);
            } catch (RuntimeException | Error failure) {
                if (stream.generation == generation) stream.reset();
                throw failure;
            }
            return this;
        }

        public VertexConsumer addVertex(float x, float y, float z) { return send(0, bits(x), bits(y), bits(z)); }
        public VertexConsumer setColor(int r, int g, int b, int a) { return send(1, r, g, b, a); }
        public VertexConsumer setColor(int argb) { return send(2, argb); }
        public VertexConsumer setUv(float u, float v) { return send(3, bits(u), bits(v)); }
        public VertexConsumer setUv1(int u, int v) { return send(4, u, v); }
        public VertexConsumer setUv2(int u, int v) { return send(5, u, v); }
        public VertexConsumer setNormal(float x, float y, float z) { return send(6, bits(x), bits(y), bits(z)); }
        public VertexConsumer setLineWidth(float width) { return send(7, bits(width)); }
    }
}
