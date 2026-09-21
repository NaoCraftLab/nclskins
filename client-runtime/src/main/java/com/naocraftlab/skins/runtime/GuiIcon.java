package com.naocraftlab.skins.runtime;

import java.util.Objects;

public enum GuiIcon {
    ACTION_PROVIDERS("action/providers", 16),
    ACTION_ADD_PROVIDER("action/add_provider", 16),
    ACTION_REFRESH("action/refresh", 16),
    ACTION_RENAME("action/rename", 16),
    ACTION_REMOVE("action/remove", 16),
    ACTION_EDIT("action/edit", 16),
    ACTION_DUPLICATE("action/duplicate", 16),
    ACTION_DELETE("action/delete", 16),
    ACTION_SELECT_FOLDER("action/select_folder", 16),
    ACTION_COLLAPSE_ALL("action/collapse_all", 16),
    ACTION_EXPAND_ALL("action/expand_all", 16),
    ACTION_ADD_LOOK("action/add_look", 32),
    ACTION_ADD_CAPE("action/add_cape", 32),

    EDITOR_TAB_APPEARANCE("editor/tab/appearance", 16),
    EDITOR_TAB_CAPE("editor/tab/cape", 16),

    APPEARANCE_MODEL_CLASSIC("appearance/model/classic", 64, 128),
    APPEARANCE_MODEL_SLIM("appearance/model/slim", 64, 128),

    APPEARANCE_BACK_CAPE("appearance/back/cape", 16),
    APPEARANCE_BACK_ELYTRA("appearance/back/elytra", 16),
    APPEARANCE_CAPE_NONE("appearance/cape/none", 32),

    APPEARANCE_OUTER_LAYER_HEAD_ON("appearance/outer_layer/head/on", 16),
    APPEARANCE_OUTER_LAYER_HEAD_OFF("appearance/outer_layer/head/off", 16),
    APPEARANCE_OUTER_LAYER_BODY_ALL_ON("appearance/outer_layer/body/all_on", 16),
    APPEARANCE_OUTER_LAYER_BODY_ALL_OFF("appearance/outer_layer/body/all_off", 16),
    APPEARANCE_OUTER_LAYER_BODY_BOTH_ARMS_OFF("appearance/outer_layer/body/both_arms_off", 16),
    APPEARANCE_OUTER_LAYER_BODY_LEFT_ARM_OFF("appearance/outer_layer/body/left_arm_off", 16),
    APPEARANCE_OUTER_LAYER_BODY_RIGHT_ARM_OFF("appearance/outer_layer/body/right_arm_off", 16),
    APPEARANCE_OUTER_LAYER_BODY_ONLY_ARMS_ON("appearance/outer_layer/body/only_arms_on", 16),
    APPEARANCE_OUTER_LAYER_BODY_ONLY_LEFT_ARM("appearance/outer_layer/body/only_left_arm", 16),
    APPEARANCE_OUTER_LAYER_BODY_ONLY_RIGHT_ARM("appearance/outer_layer/body/only_right_arm", 16),
    APPEARANCE_OUTER_LAYER_LEGS_ALL_ON("appearance/outer_layer/legs/all_on", 16),
    APPEARANCE_OUTER_LAYER_LEGS_ALL_OFF("appearance/outer_layer/legs/all_off", 16),
    APPEARANCE_OUTER_LAYER_LEGS_LEFT_OFF("appearance/outer_layer/legs/left_off", 16),
    APPEARANCE_OUTER_LAYER_LEGS_RIGHT_OFF("appearance/outer_layer/legs/right_off", 16),

    PROVIDER_SKIN_NO_VALUE("provider/skin/no_value", 32),
    PROVIDER_CAPE_NO_VALUE("provider/cape/no_value", 32),

    STATUS_COMPATIBILITY_EXTENDED("status/compatibility/extended", 16),
    STATUS_COMPATIBILITY_INCOMPATIBLE("status/compatibility/incompatible", 16);

    public static final String RESOURCE_PREFIX = "textures/gui/icons/";

    private final String semanticPath;
    private final int baseCanvas;
    private final int canvasHeight;

    GuiIcon(String semanticPath, int baseCanvas) {
        this(semanticPath, baseCanvas, baseCanvas);
    }

    GuiIcon(String semanticPath, int baseCanvas, int canvasHeight) {
        this.canvasHeight = canvasHeight;
        this.semanticPath = Objects.requireNonNull(semanticPath, "semanticPath");
        this.baseCanvas = baseCanvas;
    }

    public String semanticPath() {
        return semanticPath;
    }

    public int baseCanvas() {
        return baseCanvas;
    }

    public int canvasHeight() {
        return canvasHeight;
    }

    public String resourcePath() {
        return RESOURCE_PREFIX + semanticPath + ".png";
    }
}
