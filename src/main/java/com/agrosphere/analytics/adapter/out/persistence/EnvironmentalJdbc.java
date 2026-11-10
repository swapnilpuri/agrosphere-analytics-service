package com.agrosphere.analytics.adapter.out.persistence;

import com.agrosphere.analytics.domain.EnvironmentalObservation;
import com.agrosphere.analytics.domain.EnvironmentalProjection;
import static com.agrosphere.analytics.domain.EnvironmentalObservation.*;
import static com.agrosphere.analytics.domain.EnvironmentalProjection.*;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic immutable Analytics-owned evidence. No source database or raw envelope archive. */
public final class EnvironmentalJdbc {
    private final JdbcTemplate db;
    private final PlatformTransactionManager manager;
    public EnvironmentalJdbc(JdbcTemplate db, PlatformTransactionManager manager) { this.db=db; this.manager=manager; }
    private TransactionTemplate tx(boolean read) {
        var tx=new TransactionTemplate(manager); tx.setTimeout(15); tx.setReadOnly(read);
        if(read)tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); return tx;
    }
    private void limits() { db.execute("SET LOCAL lock_timeout='3s'"); db.execute("SET LOCAL statement_timeout='12s'"); }
    private static Timestamp stamp(Instant time) { return time==null?null:Timestamp.from(time); }
    private static UUID uuid(ResultSet r,String name)throws SQLException { return r.getObject(name,UUID.class); }
    private static Instant time(ResultSet r,String name)throws SQLException { var value=r.getTimestamp(name); return value==null?null:value.toInstant(); }
    private record Receipt(UUID observationId, Scope scope, String fingerprint) {}
    public Outcome accept(EnvironmentalObservation event, Instant at) { instant(at); return tx(false).execute(ignored->{
        limits();
        db.queryForObject("SELECT pg_advisory_xact_lock(20,?)",Object.class,event.eventId().hashCode());
        var receipts=db.query("SELECT * FROM anl_environment_receipt WHERE event_id=?",(r,n)->new Receipt(uuid(r,"observation_id"),new Scope(uuid(r,"estate_id"),uuid(r,"block_id")),r.getString("fingerprint")),event.eventId());
        var incoming=new Receipt(event.observationId(),scope(event),event.fingerprint());
        if(!receipts.isEmpty() && incoming.equals(receipts.getFirst()))return Outcome.DUPLICATE;
        db.queryForObject("SELECT pg_advisory_xact_lock(21,?)",Object.class,event.observationId().hashCode());
        var original=db.query("SELECT * FROM anl_environment_observation WHERE observation_id=?",this::observation,event.observationId());
        Scope oldScope=null; UUID oldId=null;
        if(!receipts.isEmpty()){oldScope=receipts.getFirst().scope();oldId=receipts.getFirst().observationId();}
        else if(!original.isEmpty()){oldScope=scope(original.getFirst());oldId=original.getFirst().observationId();}
        boolean conflict=oldScope!=null;
        var scopes=new TreeSet<Scope>(Comparator.comparing((Scope s)->s.estateId().toString()).thenComparing(s->s.blockId().toString()));
        scopes.add(scope(event)); if(conflict)scopes.add(oldScope);
        for(var s:scopes){
            // Every original trusted scope already exists; a new incoming scope starts with this event.
            if(s.equals(scope(event)))db.update("INSERT INTO anl_environment_scope VALUES(?,?,?,?) ON CONFLICT DO NOTHING",s.estateId(),s.blockId(),stamp(at),stamp(event.receivedAt()));
            if(db.queryForList("SELECT estate_id FROM anl_environment_scope WHERE estate_id=? AND block_id=? FOR UPDATE",s.estateId(),s.blockId()).isEmpty())throw new IllegalStateException("Missing original scope ledger");
        }
        var outcome=conflict?Outcome.CONFLICT:Outcome.APPLIED;
        if(receipts.isEmpty())db.update("INSERT INTO anl_environment_receipt VALUES(?,?,?,?,?,?,?)",event.eventId(),event.observationId(),event.estateId(),event.blockId(),event.fingerprint(),stamp(at),outcome.name());
        if(conflict){
            String reason=receipts.isEmpty()?"OBSERVATION_ID_CONFLICT":"EVENT_ID_CONFLICT";
            issue(oldScope,oldId,event.eventId(),reason); issue(scope(event),event.observationId(),event.eventId(),reason);
        }else db.update("INSERT INTO anl_environment_observation VALUES(?,?,?,?,?,?,'SIMULATED',?,?,?,?,?,?,?)",event.observationId(),event.eventId(),event.deviceId(),event.estateId(),event.blockId(),event.fingerprint(),stamp(event.observedAt()),stamp(event.receivedAt()),event.kind().name(),event.unit(),event.value(),stamp(event.intervalStart()),stamp(event.intervalEnd()));
        for(var s:scopes)db.update("UPDATE anl_environment_scope SET last_processed_at=greatest(last_processed_at,?) WHERE estate_id=? AND block_id=?",stamp(at),s.estateId(),s.blockId());
        db.update("UPDATE anl_environment_scope SET latest_source_at=greatest(latest_source_at,?) WHERE estate_id=? AND block_id=?",stamp(event.receivedAt()),event.estateId(),event.blockId());
        return outcome;
    }); }
    private void issue(Scope scope,UUID observation,UUID event,String reason) { db.update("INSERT INTO anl_environment_issue VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",scope.estateId(),scope.blockId(),event,observation,reason); }
    private EnvironmentalObservation observation(ResultSet r,int index)throws SQLException {
        return new EnvironmentalObservation(uuid(r,"event_id"),r.getString("fingerprint"),uuid(r,"observation_id"),uuid(r,"device_id"),uuid(r,"estate_id"),uuid(r,"block_id"),time(r,"observed_at"),time(r,"received_at"),Kind.valueOf(r.getString("kind")),r.getString("unit"),r.getString("value_text"),time(r,"interval_start"),time(r,"interval_end"));
    }
    public View read(Query q) { return tx(true).execute(ignored->{ limits(); return snapshot(q); }); }
    private View snapshot(Query q) {
        var args=new Object[]{q.estateId(),q.blockId(),stamp(q.from()),stamp(q.to())};
        var count=new BigInteger(db.queryForObject("SELECT count(*)::text FROM anl_environment_observation WHERE estate_id=? AND block_id=? AND observed_at>=? AND observed_at<?",String.class,args));
        var rows=db.query("SELECT * FROM anl_environment_observation WHERE estate_id=? AND block_id=? AND observed_at>=? AND observed_at<? ORDER BY observed_at,(observation_id::text) COLLATE \"C\" LIMIT 1000",this::observation,args);
        var counts=new EnumMap<Kind,Integer>(Kind.class); for(var k:Kind.values())counts.put(k,0); rows.forEach(f->counts.merge(f.kind(),1,Integer::sum));
        long conflict=db.queryForObject("SELECT count(DISTINCT event_id) FROM anl_environment_issue WHERE estate_id=? AND block_id=?",Long.class,q.estateId(),q.blockId());
        var metadata=db.query("SELECT last_processed_at,latest_source_at FROM anl_environment_scope WHERE estate_id=? AND block_id=?",(r,n)->new Instant[]{time(r,"last_processed_at"),time(r,"latest_source_at")},q.estateId(),q.blockId());
        return new View(rows,count,count.compareTo(BigInteger.valueOf(1000))>0,counts,count.signum()==0?"NONE":"OBSERVED",conflict>0?"INCOMPLETE":"OBSERVED_ONLY",conflict,metadata.isEmpty()?null:metadata.getFirst()[0],metadata.isEmpty()?null:metadata.getFirst()[1]);
    }
    public boolean hasIssue(UUID event) { return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM anl_environment_issue WHERE event_id=?)",Boolean.class,event)); }
    /** Guarded fixture CLI only: reconstruct the same bounded view independently from immutable facts. */
    public void verifyRebuildOwned(Query q) { var transaction=tx(false);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);transaction.executeWithoutResult(ignored->{
        limits(); db.queryForList("SELECT estate_id FROM anl_environment_scope WHERE estate_id=? AND block_id=? FOR UPDATE",q.estateId(),q.blockId());
        var rows=db.query("SELECT * FROM anl_environment_observation WHERE estate_id=? AND block_id=? AND observed_at>=? AND observed_at<?",this::observation,q.estateId(),q.blockId(),stamp(q.from()),stamp(q.to()));
        var receipts=new HashMap<UUID,EnvironmentalObservation>();var facts=new HashMap<UUID,EnvironmentalObservation>();
        for(var row:rows){receipts.put(row.eventId(),row);facts.put(row.observationId(),row);}
        var selected=new Scope(q.estateId(),q.blockId());
        var issues=new HashSet<>(db.query("SELECT * FROM anl_environment_issue WHERE estate_id=? AND block_id=?",(r,n)->new Issue(selected,uuid(r,"observation_id"),uuid(r,"event_id"),r.getString("reason")),q.estateId(),q.blockId()));
        var metadata=db.query("SELECT last_processed_at,latest_source_at FROM anl_environment_scope WHERE estate_id=? AND block_id=?",(r,n)->new Instant[]{time(r,"last_processed_at"),time(r,"latest_source_at")},q.estateId(),q.blockId());
        var state=new State(receipts,facts,issues,metadata.isEmpty()?Map.of():Map.of(selected,metadata.getFirst()[0]),metadata.isEmpty()?Map.of():Map.of(selected,metadata.getFirst()[1]));
        if(!EnvironmentalProjection.rebuild(state,q).equals(snapshot(q)))throw new IllegalStateException("Owned environmental rebuild mismatch");
    }); }
}
