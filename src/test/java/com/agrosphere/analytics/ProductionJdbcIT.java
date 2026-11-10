package com.agrosphere.analytics;

import com.agrosphere.analytics.adapter.in.messaging.CollectionEventJson;
import com.agrosphere.analytics.adapter.out.persistence.ProductionJdbc;
import com.agrosphere.analytics.domain.*;
import static com.agrosphere.analytics.domain.ProductionProjection.*;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.containers.*;
import com.github.dockerjava.api.model.*;

class ProductionJdbcIT {
    static PostgreSQLContainer pg;static JdbcTemplate owner,runtime;static ProductionJdbc store;static DriverManagerDataSource ownerDs;
    static final String login="r"+UUID.randomUUID().toString().replace("-", ""),password=UUID.randomUUID().toString();
    static final CollectionEventJson parser=new CollectionEventJson();
    static final UUID estate=UUID.fromString("00000000-0000-0000-0000-000000000001"),block=UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final Query query=new Query(estate,block,LocalDate.of(2026,10,27),LocalDate.of(2026,10,28));
    static ProductionRevision fixture(String name)throws Exception{return parser.parse(ProductionJdbcIT.class.getResourceAsStream("/collection-events/"+name+".json").readAllBytes(),"agrosphere."+name+".v2");}
    @BeforeAll static void start()throws Exception{
        pg=new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("agrosphere_analytics").withUsername("owner").withPassword(UUID.randomUUID().toString());
        pg.withLabel("agrosphere.anl.fixture","owned-jdbc-it").withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),ExposedPort.tcp(5432))));pg.start();
        ownerDs=new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());owner=new JdbcTemplate(ownerDs);
        Flyway.configure().dataSource(ownerDs).target("1").load().migrate();
        owner.update("INSERT INTO processed_event(consumer_name,event_id) VALUES('legacy',?)",UUID.randomUUID());
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ownerDs));var sql=Files.readString(Path.of("src/main/resources/db/migration/V2__production_projection.sql"));
        assertThrows(IllegalStateException.class,()->tx.execute(status->{owner.execute(sql);throw new IllegalStateException("fixture rollback");}));
        assertNull(owner.queryForObject("SELECT to_regclass('anl_collection')",String.class));
        Flyway.configure().dataSource(ownerDs).load().migrate();assertEquals(1,owner.queryForObject("SELECT count(*) FROM processed_event",Integer.class));
        try(var c=ownerDs.getConnection();var s=c.createStatement()){
            s.execute("CREATE ROLE "+login+" LOGIN PASSWORD '"+password+"' NOSUPERUSER NOCREATEDB NOCREATEROLE");
            s.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");s.execute("REVOKE TEMP ON DATABASE agrosphere_analytics FROM PUBLIC");
            s.execute("GRANT CONNECT ON DATABASE agrosphere_analytics TO "+login);s.execute("GRANT USAGE ON SCHEMA public TO "+login);
            s.execute("GRANT SELECT,INSERT ON anl_receipt,anl_seen_version,anl_issue TO "+login);
            s.execute("GRANT SELECT,INSERT,UPDATE ON anl_collection,anl_scope,anl_daily TO "+login);
        }catch(Exception failure){throw new IllegalStateException("Owned fixture provisioning failed");}
        var ds=new DriverManagerDataSource(pg.getJdbcUrl(),login,password);runtime=new JdbcTemplate(ds);store=new ProductionJdbc(runtime,new DataSourceTransactionManager(ds));
    }
    @BeforeEach void clear(){owner.execute("TRUNCATE anl_seen_version,anl_collection,anl_receipt,anl_issue,anl_scope,anl_daily");}
    @AfterAll static void stop(){if(pg!=null)pg.stop();}
    @Test void realRuntimeLoginIsDeniedSchemaAndHistoricalMutation(){
        assertEquals(login,runtime.queryForObject("SELECT current_user",String.class));
        for(var sql:List.of("CREATE TABLE denied(id int)","CREATE TEMP TABLE denied(id int)","TRUNCATE anl_receipt","UPDATE anl_receipt SET outcome='APPLIED'","DELETE FROM anl_seen_version","DELETE FROM anl_issue"))assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.execute(sql));
    }
    @Test void allFourPublishedSnapshotsMaterializeAndRebuildWithoutAnotherEffect()throws Exception{
        for(var name:List.of("LatexCollectionRecorded","DrcMeasurementRecorded","LatexCollectionAdjusted","LatexCollectionVoided")){
            var event=fixture(name);assertEquals(Outcome.APPLIED,store.accept(event,Instant.now()));assertEquals(Outcome.DUPLICATE,store.accept(event,Instant.now()));
        }
        var before=store.read(query);assertEquals(1,before.days().getFirst().metrics().voidedCollectionCount());assertEquals(new BigDecimal("0.000"),before.days().getFirst().metrics().totalWetLatexKg());
        assertEquals(BigInteger.ZERO,before.missingSourceVersionCount());assertEquals(4,owner.queryForObject("SELECT count(*) FROM anl_receipt",Integer.class));
        owner.update("UPDATE anl_daily SET wet_kg=9,pending_wet_kg=9 WHERE estate_id=? AND block_id=?",estate,block);
        store.rebuildOwned(estate,block);assertEquals(before,store.read(query));
    }
    @Test void higherBeforeCreationAndReorderedDeliveryCloseGapWithoutResurrection()throws Exception{
        var last=fixture("LatexCollectionVoided");store.accept(last,Instant.now());assertEquals(BigInteger.valueOf(3),store.read(query).missingSourceVersionCount());
        for(var name:List.of("LatexCollectionAdjusted","LatexCollectionRecorded","DrcMeasurementRecorded"))assertEquals(Outcome.OLDER_VERSION,store.accept(fixture(name),Instant.now()));
        assertEquals(BigInteger.ZERO,store.read(query).missingSourceVersionCount());assertEquals(1,store.read(query).days().getFirst().metrics().voidedCollectionCount());
    }
    @Test void concurrentDuplicateReceiptHasExactlyOneStoredEffect()throws Exception{
        var event=fixture("LatexCollectionRecorded");try(var pool=Executors.newFixedThreadPool(6)){
            var tasks=new ArrayList<Callable<Outcome>>();for(int n=0;n<24;n++)tasks.add(()->store.accept(event,Instant.now()));
            long applied=0;for(var result:pool.invokeAll(tasks))if(result.get()==Outcome.APPLIED)applied++;
            assertEquals(1,applied);
        }
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM anl_receipt",Integer.class));assertEquals(1,store.read(query).days().getFirst().metrics().activeCollectionCount());
    }
    @Test void parsedSyntheticCorrectionMovesBothDayBucketsAtomically()throws Exception{
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var recorded=json.readTree(getClass().getResourceAsStream("/collection-events/LatexCollectionRecorded.json").readAllBytes());
        var c=(tools.jackson.databind.node.ObjectNode)recorded.path("payload").path("collection");
        c.put("tappingDate","2026-10-26");c.put("collectedAt","2026-10-26T10:00:00Z");c.put("collectionDate","2026-10-26");c.put("backdateReason","SIMULATED historical boundary fixture");
        ((tools.jackson.databind.node.ObjectNode)recorded.path("payload")).put("reason","SIMULATED historical boundary fixture");
        store.accept(parser.parse(json.writeValueAsBytes(recorded),"agrosphere.LatexCollectionRecorded.v2"),Instant.now());
        var q=new Query(estate,block,LocalDate.of(2026,10,26),LocalDate.of(2026,10,27));
        assertEquals(new BigDecimal("125.500"),store.read(q).days().getFirst().metrics().totalWetLatexKg());
        var adjusted=json.readTree(getClass().getResourceAsStream("/collection-events/LatexCollectionAdjusted.json").readAllBytes());
        ((tools.jackson.databind.node.ObjectNode)adjusted.path("payload").path("collection")).put("tappingDate","2026-10-26");
        store.accept(parser.parse(json.writeValueAsBytes(adjusted),"agrosphere.LatexCollectionAdjusted.v2"),Instant.now());
        var view=store.read(q);assertNull(view.days().getFirst().metrics());
        assertEquals(new BigDecimal("100.000"),view.days().getLast().metrics().totalWetLatexKg());
        assertEquals(new BigDecimal("32.400"),view.days().getLast().metrics().knownDryRubberKg());
        assertEquals(BigInteger.ONE,view.missingSourceVersionCount());
        assertEquals(2,owner.queryForObject("SELECT count(*) FROM anl_receipt",Integer.class));
    }
    @Test void injectedWriteFailureRollsBackReceiptHighestSeenScopeAndDaily()throws Exception{
        var event=fixture("LatexCollectionRecorded");
        for(var table:List.of("anl_receipt","anl_collection","anl_seen_version","anl_scope","anl_daily")){
            owner.execute("CREATE FUNCTION anl_fixture_fail_write() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''fixture failure''; END'");
            owner.execute("REVOKE ALL ON FUNCTION anl_fixture_fail_write() FROM PUBLIC");owner.execute("GRANT EXECUTE ON FUNCTION anl_fixture_fail_write() TO "+login);
            owner.execute("CREATE TRIGGER fail_write BEFORE INSERT OR UPDATE ON "+table+" FOR EACH ROW EXECUTE FUNCTION anl_fixture_fail_write()");
            try{assertThrows(org.springframework.dao.DataAccessException.class,()->store.accept(event,Instant.now()));
                for(var check:List.of("anl_receipt","anl_collection","anl_seen_version","anl_scope","anl_daily"))assertEquals(0,owner.queryForObject("SELECT count(*) FROM "+check,Integer.class));
            }finally{owner.execute("DROP TRIGGER fail_write ON "+table);owner.execute("DROP FUNCTION anl_fixture_fail_write()");}
        }
    }
}
