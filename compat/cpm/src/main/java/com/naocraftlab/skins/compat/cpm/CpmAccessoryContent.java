package com.naocraftlab.skins.compat.cpm;

import com.naocraftlab.skins.compat.cpm.mixin.CpmResolvedOtherPartsAccessor;
import com.naocraftlab.skins.compat.cpm.mixin.CpmResolvedPartsAccessor;
import com.naocraftlab.skins.compat.cpm.mixin.CpmCopyTransformsAccessor;
import com.naocraftlab.skins.compat.cpm.mixin.CpmCopyVisibilityAccessor;
import com.naocraftlab.skins.compat.cpm.mixin.CpmAnimationStateAccessor;
import com.naocraftlab.skins.compat.cpm.mixin.CpmVisibilityTargetAccessor;
import com.naocraftlab.skins.compat.cpm.mixin.CpmLegacyAnimationsAccessor;
import com.naocraftlab.skins.compat.cpm.mixin.CpmLegacyVisibilityAccessor;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.model.RenderedCube;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.TextureSheetType;
import com.tom.cpm.shared.parts.IModelPart;
import com.tom.cpm.shared.parts.IResolvedModelPart;
import com.tom.cpm.shared.parts.ModelPartDefinition;
import com.tom.cpm.shared.parts.ModelPartAnimation;
import com.tom.cpm.shared.parts.anim.ModelPartAnimationNew;
import com.tom.cpm.shared.parts.anim.AnimatorChannel;
import com.tom.cpm.shared.parts.anim.Float3Driver;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;

public record CpmAccessoryContent(boolean capeEmpty, boolean elytraEmpty) {
    public static CpmAccessoryContent unknown() { return new CpmAccessoryContent(false, false); }

    public static CpmAccessoryContent observe(ModelDefinition definition) {
        if (definition == null || definition.getResolveState() != ModelDefinition.ModelLoadingState.LOADED) return unknown();
        Set<RenderedCube> writers = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!safe(definition, ((CpmResolvedPartsAccessor) definition).nclskins$resolvedParts(), writers)) return unknown();
        var copies = ((CpmCopyTransformsAccessor) definition.getAnimations()).nclskins$copyTransforms();
        if (copies == null) return unknown();
        for (var copy : copies) {
            var visibility = (CpmCopyVisibilityAccessor) copy;
            if (visibility.nclskins$copyVisibility()) writers.add(visibility.nclskins$copyTarget());
        }
        return new CpmAccessoryContent(empty(definition, RootModelType.CAPE, writers),
                empty(definition, RootModelType.ELYTRA_LEFT, writers) && empty(definition, RootModelType.ELYTRA_RIGHT, writers));
    }

    @SuppressWarnings("deprecation")
    private static boolean safe(ModelDefinition modelDefinition, List<IResolvedModelPart> parts, Set<RenderedCube> writers) {
        if (parts == null) return false;
        for (var part : parts) {
            if (part instanceof ModelPartDefinition definition) {
                if (!safe(modelDefinition, ((CpmResolvedOtherPartsAccessor) definition).nclskins$resolvedOtherParts(), writers)) return false;
            } else if (part instanceof ModelPartAnimation legacy) {
                var declarations = ((CpmLegacyAnimationsAccessor) legacy).nclskins$legacyAnimations();
                if (declarations == null) return false;
                for (var value : declarations.values()) {
                    if (!(value instanceof CpmLegacyVisibilityAccessor declaration)) return false;
                    var targets = declaration.nclskins$legacyVisibilityTargets();
                    if (targets == null) return false;
                    for (int target : targets) writers.add(modelDefinition.getElementById(target));
                }
            } else if (part instanceof ModelPartAnimationNew animation) {
                var state = ((CpmAnimationStateAccessor) animation).nclskins$animationState();
                if (state == null || state.getAnims() == null) return false;
                for (var declared : state.getAnims().values()) {
                    if (declared == null || declared.animatorChannels == null) return false;
                    for (var channel : declared.animatorChannels.values()) {
                        if (channel == null) return false;
                        if (channel.part instanceof AnimatorChannel.CubeVisDriver visibility) {
                            if (channel.frameData != null) writers.add(modelDefinition.getElementById(
                                    ((CpmVisibilityTargetAccessor) visibility).nclskins$visibilityTarget()));
                        } else if (!(Float3Driver.getFromPart(channel.part) instanceof AnimatorChannel.CubeFloatDriver)) return false;
                    }
                }
            } else if (!(part instanceof IModelPart model) || model.getType() == null || switch (model.getType()) {
                case CUBES, MODEL_ROOT, SKIN_TYPE, SKIN, TEXTURE, PLAYER, PLAYER_PARTPOS, RENDER_EFFECT, ANIMATED_TEX, UUID_LOCK -> false;
                default -> true;
            }) return false;
        }
        return true;
    }

    private static boolean empty(ModelDefinition definition, RootModelType type, Set<RenderedCube> writers) {
        var roots = definition.getModelElementFor(type);
        if (roots == null || roots.isEmpty()) return definition.getTexture(
                type == RootModelType.CAPE ? TextureSheetType.CAPE : TextureSheetType.ELYTRA, false) == null;
        boolean[] empty = {true};
        roots.forEach(root -> {
            if (!root.isHidden() || children(root, writers)) empty[0] = false;
        });
        return empty[0];
    }

    private static boolean children(RenderedCube parent, Set<RenderedCube> writers) {
        if (parent.children == null) return false;
        for (var child : parent.children) {
            var cube = child.getCube();
            if (cube == null) return true;
            if (!cube.hidden || writers.contains(child)) return true;
        }
        return false;
    }
}
