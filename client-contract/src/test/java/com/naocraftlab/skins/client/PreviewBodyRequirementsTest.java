package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PreviewBodyRequirementsTest {
    @Test void eachRequiredConsumerKeepsItsOwnAdmission() {
        for (var cpm : PreviewModelFrames.Requirement.values()) {
            for (var ears : PreviewModelFrames.Requirement.values()) {
                var requirements = new PreviewModelFrames.BodyRequirements(cpm, ears);
                boolean cpmAdmitted = cpm == PreviewModelFrames.Requirement.ABSENT || cpm == PreviewModelFrames.Requirement.REQUIRED;
                boolean earsAdmitted = ears == PreviewModelFrames.Requirement.ABSENT || ears == PreviewModelFrames.Requirement.REQUIRED;
                assertEquals(cpmAdmitted && earsAdmitted, requirements.admitted(), cpm + "/" + ears);
                assertSame(cpm, requirements.cpm());
                assertSame(ears, requirements.ears());
            }
        }
    }

    @Test void missingRequirementIsNotOrdinary() {
        assertThrows(NullPointerException.class, () -> new PreviewModelFrames.BodyRequirements(null, PreviewModelFrames.Requirement.ABSENT));
        assertThrows(NullPointerException.class, () -> new PreviewModelFrames.BodyRequirements(PreviewModelFrames.Requirement.ABSENT, null));
    }
}
