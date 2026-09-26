package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.provider.*;
import com.naocraftlab.skins.core.service.*;
import com.naocraftlab.skins.core.png.PngValidationException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import static com.naocraftlab.skins.runtime.AccountDeliveryService.*;
import static com.naocraftlab.skins.runtime.AccountReconciliationPort.*;

interface AccountMutationEffects {
    PresetApplicationOutcome apply(PresetApplicationRequest request, java.util.function.BooleanSupplier current);
    PresetApplicationOutcome retryCape(String capeId, java.util.function.BooleanSupplier current);
    ReconciliationResult result(AccountAppearanceState state, SessionValidation validation) throws IOException;
    ReconciliationResult afterMutation(AccountAppearanceState state, PresetApplicationOutcome outcome, AppearanceProviders expected);
}
