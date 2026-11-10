package com.agrosphere.analytics.application;

import java.util.*;
import com.agrosphere.analytics.application.port.out.EnvironmentalPorts.*;
import com.agrosphere.analytics.domain.EnvironmentalProjection.*;

/** Roles/estate IDs must come from the future trusted signed-token adapter, never query input. */
public final class ReadEnvironmentalHistory {
    private final ActiveBlockAccess references;
    private final HistoryReader projections;
    public ReadEnvironmentalHistory(ActiveBlockAccess references, HistoryReader projections) {
        this.references = Objects.requireNonNull(references); this.projections = Objects.requireNonNull(projections);
    }
    public View read(Query query, Set<String> roles, Set<UUID> estateIds) {
        Objects.requireNonNull(query); Objects.requireNonNull(roles); Objects.requireNonNull(estateIds);
        boolean global = roles.contains("PLATFORM_ADMIN") || roles.contains("PLANTATION_ADMIN");
        boolean assigned = estateIds.contains(query.estateId()) && roles.stream().anyMatch(Set.of("ESTATE_ADMIN", "ESTATE_MANAGER", "AUDITOR")::contains);
        if (!global && !assigned) throw new SecurityException("Environmental history access denied");
        references.requireActiveBlock(query);
        return projections.read(query);
    }
}
