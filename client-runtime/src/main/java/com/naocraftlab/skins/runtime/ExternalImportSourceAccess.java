package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.importing.ExternalAppearanceRecord;
import com.naocraftlab.skins.core.importing.ExternalImportBatch;
import com.naocraftlab.skins.core.importing.ExternalImportContext;
import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

interface ExternalImportSourceAccess {
    ExternalImportProbe probe(ExternalImportSource source, Optional<Path> selectedRoot, ExternalImportContext context);
    ExternalImportBatch discover(ExternalImportSource source, Optional<Path> selectedRoot, ExternalImportContext context) throws Exception;
    Resolution resolve(ExternalAppearanceRecord record) throws Exception;

    record Resolution(
            byte[] pngBytes, SkinVariant variant, PersonalSkinSource source) {
        public Resolution {
            pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
            Objects.requireNonNull(variant, "variant");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public byte[] pngBytes() {
            return pngBytes.clone();
        }
    }

}
