package com.naocraftlab.skins.server;

import java.util.List;
import java.util.concurrent.CompletionStage;


public interface BatchAppearancePublisher {
    CompletionStage<BatchPublicationResult> publishBatch(List<PublicationRequest> requests);


    void supersede(ConnectionKey connection);
}
