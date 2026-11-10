package com.agrosphere.analytics;

import com.agrosphere.analytics.adapter.out.persistence.EnvironmentalJdbc;
import com.agrosphere.analytics.domain.EnvironmentalObservation;
import static com.agrosphere.analytics.domain.EnvironmentalProjection.*;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

class EnvironmentalJdbcIT {
    static PostgreSQLContainer pg;static JdbcTemplate owner,runtime;static DriverManagerDataSource ownerDs;static EnvironmentalJdbc store;
    static final String login=ProductionJdbcIT.login,password=ProductionJdbcIT.password;
    static final UUID estate=UUID.fromString(EnvironmentalEventJsonTest.id(2)),block=UUID.fromString(EnvironmentalEventJsonTest.id(3));
    static final Query query=new Query(estate,block,Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2026-01-02T00:00:00Z"));
    static final Instant at=Instant.parse("2026-01-02T00:00:01Z");
    static EnvironmentalObservation fixture(){return EnvironmentalEventJsonTest.parse(EnvironmentalEventJsonTest.event());}
    @BeforeAll static void start()throws Exception {
        ProductionJdbcIT.start();pg=ProductionJdbcIT.pg;owner=ProductionJdbcIT.owner;ownerDs=ProductionJdbcIT.ownerDs;runtime=ProductionJdbcIT.runtime;
        owner.execute("GRANT SELECT,INSERT ON anl_environment_receipt,anl_environment_observation,anl_environment_issue TO "+login);
        owner.execute("GRANT SELECT,INSERT,UPDATE ON anl_environment_scope TO "+login);
        store=new EnvironmentalJdbc(runtime,new DataSourceTransactionManager(runtime.getDataSource()));
    }
    @AfterAll static void stop(){ProductionJdbcIT.stop();}
    @BeforeEach void clear(){owner.execute("TRUNCATE anl_environment_observation,anl_environment_receipt,anl_environment_issue,anl_environment_scope");}
    @Test void immutableEvidenceAndScopeWideConflictMatchPureOracleAndRebuild() {
        store.verifyRebuildOwned(query);
        var event=fixture();assertEquals(Outcome.APPLIED,store.accept(event,at));assertEquals(Outcome.DUPLICATE,store.accept(event,at.plusMillis(1)));
        var before=store.read(query);assertEquals("-2.125000",before.observations().getFirst().value());assertEquals("1",before.totalMatchingCount().toString());
        var changed=EnvironmentalEventJsonTest.event();changed.put("correlationId",EnvironmentalEventJsonTest.id(99));
        assertEquals(Outcome.CONFLICT,store.accept(EnvironmentalEventJsonTest.parse(changed),at));
        var reused=EnvironmentalEventJsonTest.event();reused.put("eventId",EnvironmentalEventJsonTest.id(90));
        assertEquals(Outcome.CONFLICT,store.accept(EnvironmentalEventJsonTest.parse(reused),at));
        assertEquals(Outcome.DUPLICATE,store.accept(EnvironmentalEventJsonTest.parse(reused),at.plusMillis(1)));
        assertEquals(before.observations(),store.read(query).observations());assertEquals(2,store.read(query).conflictCount());
        var narrow=new Query(estate,block,query.from().plusSeconds(1),query.to());assertEquals("NONE",store.read(narrow).availability());assertEquals("INCOMPLETE",store.read(narrow).projectionState());
        store.verifyRebuildOwned(query);store.verifyRebuildOwned(narrow);
        assertEquals(2,owner.queryForObject("SELECT count(*) FROM anl_environment_receipt",Integer.class));
    }
    @Test void crossScopeConflictPreservesOriginalAndMarksBothTrustedScopes() {
        store.accept(fixture(),at);var e=EnvironmentalEventJsonTest.event();e.put("estateId",EnvironmentalEventJsonTest.id(50));e.put("blockId",EnvironmentalEventJsonTest.id(51));
        var p=(tools.jackson.databind.node.ObjectNode)e.path("payload");p.put("estateId",e.path("estateId").asText());p.put("blockId",e.path("blockId").asText());
        var changed=EnvironmentalEventJsonTest.parse(e);assertEquals(Outcome.CONFLICT,store.accept(changed,at));
        assertEquals(1,store.read(query).conflictCount());var other=new Query(changed.estateId(),changed.blockId(),query.from(),query.to());
        assertEquals("NONE",store.read(other).availability());assertEquals(1,store.read(other).conflictCount());store.verifyRebuildOwned(other);
    }
    @Test void concurrentDuplicateReceiptAndObservationReuseHaveOneFact()throws Exception {
        try(var pool=Executors.newFixedThreadPool(6)) {
            var tasks=new ArrayList<Callable<Outcome>>();for(int n=0;n<24;n++)tasks.add(()->store.accept(fixture(),at));
            long applied=0;for(var f:pool.invokeAll(tasks))if(f.get()==Outcome.APPLIED)applied++;assertEquals(1,applied);
        }
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM anl_environment_observation",Integer.class));
    }
    @Test void rollbackAtEveryWriteBoundaryLeavesNoPartialReceiptFactOrMetadata() {
        for(int stage=1;stage<=5;stage++) {
            final int failAt=stage;
            var fault=new JdbcTemplate(runtime.getDataSource()) {int count;@Override public int update(String sql,Object...args){int result=super.update(sql,args);if(++count==failAt)throw new IllegalStateException("owned write failure");return result;}};
            var failed=new EnvironmentalJdbc(fault,new DataSourceTransactionManager(runtime.getDataSource()));
            assertThrows(IllegalStateException.class,()->failed.accept(fixture(),at));
            for(var table:List.of("receipt","observation","issue","scope"))assertEquals(0,owner.queryForObject("SELECT count(*) FROM anl_environment_"+table,Integer.class));
        }
        assertEquals(Outcome.APPLIED,store.accept(fixture(),at));assertEquals(Outcome.DUPLICATE,store.accept(fixture(),at)); // unknown-commit redelivery
    }
    @Test void exact1000BoundaryOrderingCountsAndLateArrivalAreStable() {
        for(int i=1001;i>=1;i--){var e=EnvironmentalEventJsonTest.event();e.put("eventId",EnvironmentalEventJsonTest.id(10000+i));e.put("aggregateId",EnvironmentalEventJsonTest.id(20000+i));((tools.jackson.databind.node.ObjectNode)e.path("payload")).put("observationId",EnvironmentalEventJsonTest.id(20000+i));store.accept(EnvironmentalEventJsonTest.parse(e),at);if(i==2)assertFalse(store.read(query).truncated());}
        var value=store.read(query);assertEquals("1001",value.totalMatchingCount().toString());assertEquals(1000,value.observations().size());assertTrue(value.truncated());
        assertEquals(EnvironmentalEventJsonTest.id(20001),value.observations().getFirst().observationId().toString());store.verifyRebuildOwned(query);
    }
    @Test void runtimeCannotMutateImmutableEvidenceOrDdl() {
        for(var sql:List.of("CREATE TABLE denied(id int)","CREATE TEMP TABLE denied(id int)","TRUNCATE anl_environment_observation","UPDATE anl_environment_observation SET value_text='1'","DELETE FROM anl_environment_receipt","UPDATE anl_environment_receipt SET outcome='APPLIED'","DELETE FROM anl_environment_issue"))assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.execute(sql));
    }
    @Test void exactV3MigrationRollsBackAndUpgradesV2WithoutLosingPriorMarker()throws Exception {
        String schema="env_upgrade_"+UUID.randomUUID().toString().replace("-","");
        Flyway.configure().dataSource(ownerDs).schemas(schema).defaultSchema(schema).target("2").load().migrate();
        owner.execute("INSERT INTO "+schema+".processed_event(consumer_name,event_id) VALUES('retained',gen_random_uuid())");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ownerDs));var ddl=Files.readString(Path.of("src/main/resources/db/migration/V3__environmental_history.sql"));
        assertThrows(IllegalStateException.class,()->tx.execute(status->{owner.execute("SET LOCAL search_path TO "+schema);owner.execute(ddl);throw new IllegalStateException("owned migration rollback");}));
        assertNull(owner.queryForObject("SELECT to_regclass(?)",String.class,schema+".anl_environment_observation"));
        Flyway.configure().dataSource(ownerDs).schemas(schema).defaultSchema(schema).target("3").load().migrate();
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM "+schema+".processed_event",Integer.class));
    }
}
