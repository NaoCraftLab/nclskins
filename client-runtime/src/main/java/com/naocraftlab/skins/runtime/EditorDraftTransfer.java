package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.model.LocalCapeReference;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

record EditorDraftTransfer(PresetEditorModel draft, LocalCapeReference capeSeed,
        Optional<PersonalSkinSource> source, UUID selectedPresetId, Optional<LibraryEditorPort.EditorSaveScope> saveScope) {
    EditorDraftTransfer(PresetEditorModel draft, LocalCapeReference capeSeed,
            Optional<PersonalSkinSource> source, UUID selectedPresetId) {
        this(draft, capeSeed, source, selectedPresetId, Optional.empty());
    }
    EditorDraftTransfer {
        Objects.requireNonNull(draft, "draft");
        source = Objects.requireNonNull(source, "source");
        saveScope = Objects.requireNonNull(saveScope, "saveScope");
        if (draft.convertedCreation() && saveScope.isEmpty()) throw new IllegalArgumentException("Converted draft requires initiating scope");
    }
}
