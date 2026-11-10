package com.agrosphere.analytics.application.port.out;

import java.time.Instant;
import com.agrosphere.analytics.domain.ProductionRevision;
import com.agrosphere.analytics.domain.ProductionProjection.*;

/** Contracts for later reviewed adapters; no implementation, live endpoint or sibling dependency. */
public final class ProductionPorts {
    private ProductionPorts() {}
    public interface ActiveBlockAccess { void requireActiveBlock(Query query); }
    public interface HistoryReader { View read(Query query); }
    /** Validate full envelope first; atomically persist receipt, highest ledger, issues and daily reverse/apply.
     * Commit before ACK; rollback is never success. Retry/DLQ/credentials remain adapter review gates. */
    public interface ProjectionTransaction { Outcome commit(ProductionRevision event, Instant processedAt); }
}
