package com.agrosphere.analytics;

import com.agrosphere.analytics.adapter.in.messaging.CollectionEventJson;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class ProductionHttpIT {
    static HttpServer identity;static RSAKey key;static String issuer,url;static final AtomicInteger references=new AtomicInteger();
    static volatile int referenceStatus=200;static ConfigurableApplicationContext app;static Path dir;static String run;static byte[] manifest;
    static final HttpClient http=HttpClient.newHttpClient();static final JsonMapper json=JsonMapper.builder().build();
    static String sha(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    @BeforeAll static void start()throws Exception{
        ProductionJdbcIT.start();key=new RSAKeyGenerator(2048).keyID("owned-fixture").generate();
        identity=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);url="http://127.0.0.1:"+identity.getAddress().getPort();issuer=url+"/realms/owned";
        identity.createContext("/certs",ex->{var body=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","application/json");ex.sendResponseHeaders(200,body.length);ex.getResponseBody().write(body);ex.close();});
        identity.createContext("/api/",ex->{references.incrementAndGet();int code=referenceStatus;if(ex.getRequestHeaders().getFirst("Authorization")==null)code=403;
            String body="{}";if(code==200)body=ex.getRequestURI().getPath().contains("/estates/")?"{\"id\":\""+ProductionJdbcIT.estate+"\",\"status\":\"ACTIVE\"}":"{\"id\":\""+ProductionJdbcIT.block+"\",\"estateId\":\""+ProductionJdbcIT.estate+"\",\"status\":\"ACTIVE\",\"cropType\":\"RUBBER\"}";
            var bytes=body.getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","application/json");ex.sendResponseHeaders(code,bytes.length);ex.getResponseBody().write(bytes);ex.close();});identity.start();
        run=UUID.randomUUID().toString();dir=Files.createTempDirectory(Path.of("target"),"anl-http-");Files.writeString(dir.resolve(".generated-analytics-owner"),run);
        var now=Instant.now().minusSeconds(1);manifest=json.writeValueAsBytes(Map.of("runId",run,"projectId","agrosphere-3a-check-"+run.substring(0,8),"createdAt",now.toString(),"expiresAt",now.plusSeconds(3600).toString(),"dbUrlSha256",sha(ProductionJdbcIT.pg.getJdbcUrl().getBytes(StandardCharsets.UTF_8)),"runtimeUser",ProductionJdbcIT.login,"migrationUser",ProductionJdbcIT.pg.getUsername(),"rabbitUser","unused-owned-consumer","scopes",List.of(Map.of("estateId",ProductionJdbcIT.estate.toString(),"blockId",ProductionJdbcIT.block.toString()))));
        Files.write(dir.resolve("manifest.json"),manifest);ProductionJdbcIT.owner.execute("COMMENT ON DATABASE agrosphere_analytics IS 'ANL01:"+run+"'");
        var properties=new HashMap<String,Object>();properties.put("server.port",0);properties.put("spring.security.oauth2.resourceserver.jwt.issuer-uri",issuer);properties.put("OIDC_JWK_SET_URI",url+"/certs");
        properties.put("spring.datasource.url",ProductionJdbcIT.pg.getJdbcUrl());properties.put("spring.datasource.username",ProductionJdbcIT.login);properties.put("spring.datasource.password",ProductionJdbcIT.password);
        properties.put("spring.flyway.user",ProductionJdbcIT.pg.getUsername());properties.put("spring.flyway.password",ProductionJdbcIT.pg.getPassword());
        properties.put("spring.rabbitmq.username","unused-owned-consumer");properties.put("spring.rabbitmq.password",UUID.randomUUID().toString());
        properties.put("analytics.demo.enabled",true);properties.put("analytics.ingestion.enabled",false);properties.put("ANALYTICS_FIXTURE_MANIFEST",dir.resolve("manifest.json").toString());properties.put("ANALYTICS_FIXTURE_SHA256",sha(manifest));
        properties.put("ANALYTICS_ESTATE_URL",url);properties.put("management.tracing.enabled",false);
        properties.put("management.otlp.metrics.export.enabled",false);properties.put("debug",false);
        properties.put("logging.level.root","INFO");properties.put("logging.level.org.springframework.web","INFO");properties.put("logging.level.org.springframework.jdbc","INFO");
        var application=new SpringApplication(Application.class);application.setDefaultProperties(properties);
        application.addInitializers(context->context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("owned-fixture",properties)));app=application.run();
        url="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();
    }
    @AfterAll static void stop()throws Exception{
        if(app!=null)app.close();if(identity!=null)identity.stop(0);ProductionJdbcIT.stop();
        if(dir!=null){assertEquals(run,Files.readString(dir.resolve(".generated-analytics-owner")));Files.deleteIfExists(dir.resolve("manifest.json"));Files.delete(dir.resolve(".generated-analytics-owner"));Files.delete(dir);}
    }
    @BeforeEach void reset()throws Exception{new ProductionJdbcIT().clear();referenceStatus=200;references.set(0);Files.write(dir.resolve("manifest.json"),manifest);}
    static String token(String role,UUID estate,String audience,String tokenIssuer,Instant expiry,RSAKey signer)throws Exception{
        var claims=new JWTClaimsSet.Builder().issuer(tokenIssuer).subject("generated-http-fixture").audience(audience).issueTime(new Date()).expirationTime(Date.from(expiry)).claim("realm_access",Map.of("roles",List.of(role))).claim("estate_ids",List.of(estate.toString())).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("owned-fixture").build(),claims);jwt.sign(new RSASSASigner(signer));return jwt.serialize();
    }
    static String manager()throws Exception{return token("ESTATE_MANAGER",ProductionJdbcIT.estate,"agrosphere-api",issuer,Instant.now().plusSeconds(600),key);}
    static HttpResponse<byte[]> get(String bearer,String extra)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(url+"/api/v1/analytics/estates/"+ProductionJdbcIT.estate+"/blocks/"+ProductionJdbcIT.block+"/production/daily?from=2026-10-27&to=2026-10-28"+extra)).timeout(Duration.ofSeconds(15));if(bearer!=null)request.header("Authorization","Bearer "+bearer);return http.send(request.GET().build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    @Test void actualHttpEmptyPendingAndMaterializedValuesMatchPinnedSchema()throws Exception{
        var empty=get(manager(),"");assertEquals(200,empty.statusCode());new CollectionEventJson().validateResponse(empty.body());assertTrue(json.readTree(empty.body()).path("days").get(0).path("metrics").isNull());
        ProductionJdbcIT.store.accept(ProductionJdbcIT.fixture("LatexCollectionRecorded"),Instant.now());
        var pending=get(manager(),"");assertEquals(200,pending.statusCode());new CollectionEventJson().validateResponse(pending.body());
        assertEquals("125.500",json.readTree(pending.body()).path("days").get(0).path("metrics").path("totalWetLatexKg").asText());assertTrue(json.readTree(pending.body()).path("days").get(0).path("metrics").path("knownDryRubberKg").isNull());
    }
    @Test void signedIssuerAudienceExpirySignatureAndRoleEstateBoundaries()throws Exception{
        assertEquals(401,get(null,"").statusCode());
        for(var token:List.of(token("ESTATE_MANAGER",ProductionJdbcIT.estate,"wrong",issuer,Instant.now().plusSeconds(600),key),token("ESTATE_MANAGER",ProductionJdbcIT.estate,"agrosphere-api",issuer+"-wrong",Instant.now().plusSeconds(600),key),token("ESTATE_MANAGER",ProductionJdbcIT.estate,"agrosphere-api",issuer,Instant.now().minusSeconds(300),key),token("ESTATE_MANAGER",ProductionJdbcIT.estate,"agrosphere-api",issuer,Instant.now().plusSeconds(600),new RSAKeyGenerator(2048).keyID("owned-fixture").generate())))assertEquals(401,get(token,"").statusCode());
        for(var token:List.of(token("VIEWER",ProductionJdbcIT.estate,"agrosphere-api",issuer,Instant.now().plusSeconds(600),key),token("ESTATE_MANAGER",UUID.randomUUID(),"agrosphere-api",issuer,Instant.now().plusSeconds(600),key)))assertEquals(403,get(token,"").statusCode());
        assertEquals(0,references.get());
    }
    @Test void invalidQueryReferenceDenialAndOutageAreDistinct()throws Exception{
        assertEquals(400,get(manager(),"&workerId=forbidden").statusCode());assertEquals(400,get(manager(),"&from=2026-10-27").statusCode());assertEquals(0,references.get());
        for(int code:List.of(403,404,503)){referenceStatus=code;assertEquals(code,get(manager(),"").statusCode());}
    }
    @Test void changedOwnedFixtureStopsReadRatherThanReturningSuccessfulEmptyData()throws Exception{
        Files.writeString(dir.resolve("manifest.json"),"{}");assertEquals(503,get(manager(),"").statusCode());assertEquals(0,references.get());
    }
    @Test void disabledMigrationStrategyTargetsOnlyFoundationWithoutReadingManifest(){
        var schema="anl_disabled_"+UUID.randomUUID().toString().replace("-","");
        var flyway=Flyway.configure().dataSource(ProductionJdbcIT.ownerDs).schemas(schema).defaultSchema(schema).load();
        new AnalyticsMigrationConfiguration().analyticsMigrations(new MockEnvironment().withProperty("analytics.demo.enabled","false"),ProductionJdbcIT.ownerDs).migrate(flyway);
        assertNull(ProductionJdbcIT.owner.queryForObject("SELECT to_regclass(?)",String.class,schema+".anl_collection"));
        assertEquals("1",flyway.info().current().getVersion().toString());
    }
}
