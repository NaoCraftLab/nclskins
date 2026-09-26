package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.model.LocalCapeReference;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

record EditorDraftTransfer(PresetEditorModel draft, LocalCapeReference capeSeed,
        Optional<PersonalSkinSource> source, UUID selectedPresetId) {
    EditorDraftTransfer {
        Objects.requireNonNull(draft, "draft");
        source = Objects.requireNonNull(source, "source");
    }
}
