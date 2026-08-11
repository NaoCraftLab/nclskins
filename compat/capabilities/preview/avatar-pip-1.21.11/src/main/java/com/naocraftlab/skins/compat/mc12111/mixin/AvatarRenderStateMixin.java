package com.naocraftlab.skins.compat.mc12111.mixin;

import com.naocraftlab.skins.compat.mc12111.NclPreviewState;
import com.naocraftlab.skins.compat.mc12111.Minecraft12111PreviewContext;
import com.naocraftlab.skins.compat.mc12111.Minecraft12111PreviewFailureSink;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;


@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateMixin implements NclPreviewState {
    @Unique
    private boolean nclskins$editorPreview;

    @Unique
    private Minecraft12111PreviewContext nclskins$previewContext;

    @Unique
    private Minecraft12111PreviewFailureSink nclskins$failureSink;

    @Unique
    private Minecraft12111PreviewFailureSink nclskins$layerFailureSink;

    @Override
    public boolean nclskins$isEditorPreview() {
        return nclskins$editorPreview;
    }

    @Override
    public void nclskins$setEditorPreview(boolean value) {
        nclskins$editorPreview = value;
    }

    @Override
    public Minecraft12111PreviewContext nclskins$previewContext() {
        return nclskins$previewContext;
    }

    @Override
    public void nclskins$setPreviewContext(Minecraft12111PreviewContext context) {
        nclskins$previewContext = context;
    }

    @Override
    public Minecraft12111PreviewFailureSink nclskins$failureSink() {
        return nclskins$failureSink;
    }

    @Override
    public void nclskins$setFailureSink(Minecraft12111PreviewFailureSink sink) {
        nclskins$failureSink = sink;
    }

    @Override
    public Minecraft12111PreviewFailureSink nclskins$layerFailureSink() {
        return nclskins$layerFailureSink;
    }

    @Override
    public void nclskins$setLayerFailureSink(Minecraft12111PreviewFailureSink sink) {
        nclskins$layerFailureSink = sink;
    }
}
