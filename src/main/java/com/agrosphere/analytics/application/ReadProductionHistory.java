package com.agrosphere.analytics.application;

import java.util.*;
import com.agrosphere.analytics.application.port.out.ProductionPorts.*;
import com.agrosphere.analytics.domain.ProductionProjection.*;

/** Trusted signed-claim mapping is a future security adapter, not established by these pure rules. */
public final class ReadProductionHistory {
    private final ActiveBlockAccess references;
    private final HistoryReader projections;
    public ReadProductionHistory(ActiveBlockAccess references, HistoryReader projections) {
        this.references = Objects.requireNonNull(references); this.projections = Objects.requireNonNull(projections);
    }
    public View read(Query query, Set<String> roles, Set<UUID> estateIds) {
        Objects.requireNonNull(roles); Objects.requireNonNull(estateIds);
        boolean global = roles.contains("PLATFORM_ADMIN") || roles.contains("PLANTATION_ADMIN");
        boolean assigned = estateIds.contains(query.estateId()) && roles.stream().anyMatch(Set.of("ESTATE_ADMIN", "ESTATE_MANAGER", "AUDITOR")::contains);
        if (!global && !assigned) throw new SecurityException("Production history access denied");
        references.requireActiveBlock(query);
        return projections.read(query);
    }
}
