package com.agrosphere.analytics.adapter.out.persistence;

import com.agrosphere.analytics.domain.*;
import static com.agrosphere.analytics.domain.ProductionProjection.*;
import static com.agrosphere.analytics.domain.ProductionRevision.*;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Analytics-owned atomic receipt/highest-ledger/materialization; never reads another service DB. */
public final class ProductionJdbc {
    private final JdbcTemplate db;
    private final PlatformTransactionManager manager;
    public ProductionJdbc(JdbcTemplate db,PlatformTransactionManager manager){this.db=db;this.manager=manager;}
    private TransactionTemplate tx(boolean read){var tx=new TransactionTemplate(manager);tx.setTimeout(15);tx.setReadOnly(read);if(read)tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx;}
    private static Timestamp stamp(Instant value){return value==null?null:Timestamp.from(value);}
    private static UUID uuid(ResultSet r,String key)throws SQLException{return r.getObject(key,UUID.class);}
    public Outcome accept(ProductionRevision event,Instant at){return tx(false).execute(status->{
        db.execute("SET LOCAL lock_timeout='3s'");db.execute("SET LOCAL statement_timeout='12s'");
        db.queryForObject("SELECT pg_advisory_xact_lock(10,?)",Object.class,event.eventId().hashCode());
        var receipts=db.query("SELECT * FROM anl_receipt WHERE event_id=?",(r,n)->new Receipt(uuid(r,"collection_id"),r.getLong("version"),new Scope(uuid(r,"estate_id"),uuid(r,"block_id")),r.getString("fingerprint")),event.eventId());
        db.queryForObject("SELECT pg_advisory_xact_lock(11,?)",Object.class,event.collectionId().hashCode());
        var highest=db.query("SELECT * FROM anl_collection WHERE collection_id=?",this::revision,event.collectionId());
        var scopes=new TreeSet<Scope>(Comparator.comparing((Scope x)->x.estateId().toString()).thenComparing(x->x.blockId().toString()));
        scopes.add(new Scope(event.estateId(),event.blockId()));if(!receipts.isEmpty())scopes.add(receipts.getFirst().scope());
        if(!highest.isEmpty())scopes.add(new Scope(highest.getFirst().estateId(),highest.getFirst().blockId()));
        for(var scope:scopes){db.update("INSERT INTO anl_scope(estate_id,block_id) VALUES(?,?) ON CONFLICT DO NOTHING",scope.estateId(),scope.blockId());db.queryForList("SELECT estate_id FROM anl_scope WHERE estate_id=? AND block_id=? FOR UPDATE",scope.estateId(),scope.blockId());}
        Map<UUID,Receipt> oldReceipts=receipts.isEmpty()?Map.of():Map.of(event.eventId(),receipts.getFirst());
        Map<UUID,History> oldRoots=new HashMap<>();
        if(!highest.isEmpty()){
            var seen=new HashMap<Long,UUID>();db.query("SELECT version,event_id FROM anl_seen_version WHERE collection_id=?",r->{seen.put(r.getLong(1),r.getObject(2,UUID.class));},event.collectionId());
            oldRoots.put(event.collectionId(),new History(highest.getFirst(),seen));
        }
        var processed=new HashMap<Scope,Instant>();var source=new HashMap<Scope,Instant>();
        for(var scope:scopes)db.query("SELECT last_processed_at,latest_source_at FROM anl_scope WHERE estate_id=? AND block_id=?",r->{if(r.getTimestamp(1)!=null)processed.put(scope,r.getTimestamp(1).toInstant());if(r.getTimestamp(2)!=null)source.put(scope,r.getTimestamp(2).toInstant());},scope.estateId(),scope.blockId());
        var transition=ProductionProjection.apply(new State(oldReceipts,oldRoots,Set.of(),processed,source),event,at);
        if(transition.outcome()==Outcome.DUPLICATE)return Outcome.DUPLICATE;
        var state=transition.state();
        if(receipts.isEmpty())db.update("INSERT INTO anl_receipt VALUES(?,?,?,?,?,?,?,?)",event.eventId(),event.collectionId(),event.version(),event.estateId(),event.blockId(),event.fingerprint(),stamp(at),transition.outcome().name());
        for(var issue:state.issues())db.update("INSERT INTO anl_issue VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",issue.scope().estateId(),issue.scope().blockId(),issue.eventId(),issue.collectionId(),issue.reason());
        var next=state.collections().get(event.collectionId());
        if(transition.outcome()==Outcome.APPLIED){
            var r=next.latest();
            db.update("INSERT INTO anl_collection VALUES(?,?,?,?,?,?,?,?,?,?,'SIMULATED',?,?) ON CONFLICT(collection_id) DO UPDATE SET collection_date=EXCLUDED.collection_date,version=EXCLUDED.version,event_id=EXCLUDED.event_id,source_at=EXCLUDED.source_at,fingerprint=EXCLUDED.fingerprint,status=EXCLUDED.status,wet_kg=EXCLUDED.wet_kg,dry_kg=EXCLUDED.dry_kg",
                r.collectionId(),r.estateId(),r.blockId(),java.sql.Date.valueOf(r.collectionDate()),r.estateTimezone().getId(),r.version(),r.eventId(),stamp(r.sourceAt()),r.fingerprint(),r.status().name(),r.wetKg(),r.dryKg());
        }
        if(transition.outcome()==Outcome.APPLIED||transition.outcome()==Outcome.OLDER_VERSION)
            db.update("INSERT INTO anl_seen_version VALUES(?,?,?) ON CONFLICT DO NOTHING",event.collectionId(),event.version(),event.eventId());
        if(transition.outcome()==Outcome.APPLIED){
            var dates=new TreeSet<LocalDate>();dates.add(event.collectionDate());if(!highest.isEmpty())dates.add(highest.getFirst().collectionDate());
            for(var day:dates)materialize(event.estateId(),event.blockId(),day);
        }
        for(var scope:scopes)db.update("UPDATE anl_scope SET last_processed_at=?,latest_source_at=? WHERE estate_id=? AND block_id=?",stamp(state.lastProcessedAt().get(scope)),stamp(state.latestSourceAt().get(scope)),scope.estateId(),scope.blockId());
        return transition.outcome();
    });}
    private ProductionRevision revision(ResultSet r,int row)throws SQLException{
        var status=Status.valueOf(r.getString("status"));long version=r.getLong("version");
        return new ProductionRevision(uuid(r,"event_id"),r.getString("fingerprint"),version==0?Kind.RECORDED:status==Status.VOIDED?Kind.VOIDED:Kind.ADJUSTED,r.getTimestamp("source_at").toInstant(),uuid(r,"collection_id"),version,uuid(r,"estate_id"),uuid(r,"block_id"),r.getDate("collection_date").toLocalDate(),ZoneId.of(r.getString("estate_timezone")),Provenance.SIMULATED,status,r.getBigDecimal("wet_kg"),r.getBigDecimal("dry_kg"));
    }
    private void materialize(UUID estate,UUID block,LocalDate day){
        db.update("""
            INSERT INTO anl_daily(estate_id,block_id,collection_date,active_count,pending_count,calculated_count,voided_count,wet_kg,pending_wet_kg,calculated_wet_kg,known_dry_kg)
            SELECT ?,?,?,count(*) FILTER(WHERE status<>'VOIDED'),count(*) FILTER(WHERE status='PENDING_DRC'),count(*) FILTER(WHERE status='CALCULATED'),count(*) FILTER(WHERE status='VOIDED'),
              coalesce(sum(wet_kg) FILTER(WHERE status<>'VOIDED'),0),coalesce(sum(wet_kg) FILTER(WHERE status='PENDING_DRC'),0),coalesce(sum(wet_kg) FILTER(WHERE status='CALCULATED'),0),sum(dry_kg) FILTER(WHERE status='CALCULATED')
            FROM anl_collection WHERE estate_id=? AND block_id=? AND collection_date=?
            ON CONFLICT(estate_id,block_id,collection_date) DO UPDATE SET active_count=EXCLUDED.active_count,pending_count=EXCLUDED.pending_count,calculated_count=EXCLUDED.calculated_count,voided_count=EXCLUDED.voided_count,wet_kg=EXCLUDED.wet_kg,pending_wet_kg=EXCLUDED.pending_wet_kg,calculated_wet_kg=EXCLUDED.calculated_wet_kg,known_dry_kg=EXCLUDED.known_dry_kg
            """,estate,block,java.sql.Date.valueOf(day),estate,block,java.sql.Date.valueOf(day));
    }
    public View read(Query q){return tx(true).execute(ignored->{
        var rows=db.query("SELECT * FROM anl_daily WHERE estate_id=? AND block_id=? AND collection_date BETWEEN ? AND ? ORDER BY collection_date",(r,n)->new Day(r.getDate("collection_date").toLocalDate(),r.getLong("active_count")+r.getLong("voided_count")==0?null:new Metrics(r.getLong("active_count"),r.getLong("pending_count"),r.getLong("calculated_count"),r.getLong("voided_count"),r.getBigDecimal("wet_kg").setScale(3),r.getBigDecimal("pending_wet_kg").setScale(3),r.getBigDecimal("calculated_wet_kg").setScale(3),r.getBigDecimal("known_dry_kg")==null?null:r.getBigDecimal("known_dry_kg").setScale(3),r.getLong("pending_count")==0)),q.estateId(),q.blockId(),java.sql.Date.valueOf(q.from()),java.sql.Date.valueOf(q.to()));
        var byDay=new HashMap<LocalDate,Day>();for(var day:rows)byDay.put(day.date(),day);var days=new ArrayList<Day>();
        for(var d=q.from();;d=d.plusDays(1)){days.add(byDay.getOrDefault(d,new Day(d,null)));if(d.equals(q.to()))break;}
        var sources=db.query("SELECT collection_id,version,event_id,source_at,collection_date FROM anl_collection WHERE estate_id=? AND block_id=? AND collection_date BETWEEN ? AND ? ORDER BY (collection_id::text) COLLATE \"C\" LIMIT 101",(r,n)->new SourceVersion(uuid(r,"collection_id"),r.getLong("version"),uuid(r,"event_id"),r.getTimestamp("source_at").toInstant(),r.getDate("collection_date").toLocalDate()),q.estateId(),q.blockId(),java.sql.Date.valueOf(q.from()),java.sql.Date.valueOf(q.to()));
        var gap=db.queryForObject("SELECT coalesce(sum(c.version::numeric+1-(SELECT count(*) FROM anl_seen_version v WHERE v.collection_id=c.collection_id)),0) FROM anl_collection c WHERE estate_id=? AND block_id=?",BigDecimal.class,q.estateId(),q.blockId()).toBigIntegerExact();
        var conflict=db.queryForObject("SELECT count(DISTINCT event_id) FROM anl_issue WHERE estate_id=? AND block_id=? AND reason<>'UNSUPPORTED_REAL'",Long.class,q.estateId(),q.blockId());
        var unsupported=db.queryForObject("SELECT count(DISTINCT event_id) FROM anl_issue WHERE estate_id=? AND block_id=? AND reason='UNSUPPORTED_REAL'",Long.class,q.estateId(),q.blockId());
        var cursors=db.query("SELECT last_processed_at,latest_source_at FROM anl_scope WHERE estate_id=? AND block_id=?",(r,n)->new Instant[]{r.getTimestamp(1)==null?null:r.getTimestamp(1).toInstant(),r.getTimestamp(2)==null?null:r.getTimestamp(2).toInstant()},q.estateId(),q.blockId());
        return new View(days,sources.stream().limit(100).toList(),sources.size()>100,gap,conflict,unsupported,cursors.isEmpty()?null:cursors.getFirst()[0],cursors.isEmpty()?null:cursors.getFirst()[1]);
    });}
    public boolean hasIssue(UUID event){return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM anl_issue WHERE event_id=?)",Boolean.class,event));}
    /** Only the verified generated runtime may call this; no public rebuild endpoint. */
    public void rebuildOwned(UUID estate,UUID block){tx(false).executeWithoutResult(ignored->{
        db.queryForList("SELECT estate_id FROM anl_scope WHERE estate_id=? AND block_id=? FOR UPDATE",estate,block);
        var dates=db.queryForList("SELECT collection_date FROM anl_collection WHERE estate_id=? AND block_id=? UNION SELECT collection_date FROM anl_daily WHERE estate_id=? AND block_id=?",java.sql.Date.class,estate,block,estate,block);
        dates.stream().sorted().forEach(d->materialize(estate,block,d.toLocalDate()));
    });}
}
