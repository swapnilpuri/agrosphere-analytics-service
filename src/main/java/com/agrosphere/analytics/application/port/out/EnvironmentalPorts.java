package com.agrosphere.analytics.application.port.out;

import java.time.Instant;
import com.agrosphere.analytics.domain.EnvironmentalObservation;
import com.agrosphere.analytics.domain.EnvironmentalProjection.*;

/** Preparation only; adapters require subsequent runtime/security/provisioning review. */
public final class EnvironmentalPorts {
    private EnvironmentalPorts() {}
    public interface ActiveBlockAccess { void requireActiveBlock(Query query); }
    public interface HistoryReader { View read(Query query); }
    /** Full schema/identity/provenance validation and fingerprint must precede this boundary.
     * Future receipt, fact, conflict and metadata writes must commit atomically before ACK. */
    public interface ProjectionTransaction { Outcome commit(EnvironmentalObservation event, Instant processedAt); }
}
