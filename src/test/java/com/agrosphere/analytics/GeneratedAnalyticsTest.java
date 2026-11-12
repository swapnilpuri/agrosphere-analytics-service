package com.agrosphere.analytics;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.json.JsonMapper;

class GeneratedAnalyticsTest {
    Path root,file;String run=UUID.randomUUID().toString(),url="jdbc:postgresql://owned:5432/agrosphere_analytics",sha;
    Instant now=Instant.parse("2026-11-05T12:00:00Z");
    UUID estate=UUID.randomUUID(),block=UUID.randomUUID();
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @BeforeEach void create()throws Exception{
        root=Files.createTempDirectory(Path.of("target"),"owned-anl-guard-");file=root.resolve("manifest.json");
        Files.writeString(root.resolve(".generated-analytics-owner"),run);
        var value=Map.of("runId",run,"projectId","agrosphere-3a-check-abcdef12","createdAt",now.toString(),"expiresAt",now.plusSeconds(3600).toString(),
            "dbUrlSha256",hash(url.getBytes(StandardCharsets.UTF_8)),"runtimeUser","runtime","migrationUser","migration","rabbitUser","consumer",
            "scopes",List.of(Map.of("estateId",estate.toString(),"blockId",block.toString())));
        Files.write(file,JsonMapper.builder().build().writeValueAsBytes(value));sha=hash(Files.readAllBytes(file));
    }
    @AfterEach void remove()throws Exception{Files.deleteIfExists(file);Files.deleteIfExists(root.resolve(".generated-analytics-owner"));Files.delete(root);}
    GeneratedAnalytics fixture(Instant time){return new GeneratedAnalytics(file,sha,Clock.fixed(time,ZoneOffset.UTC));}
    @Test void exactScopeBindingsAndLifetimeFailClosed(){
        var fixture=fixture(now);fixture.permit(estate,block);fixture.verifyBindings(url,"runtime","migration","consumer");
        assertThrows(SecurityException.class,()->fixture.permit(estate,UUID.randomUUID()));
        assertThrows(SecurityException.class,()->fixture.verifyBindings(url+"-other","runtime","migration","consumer"));
        assertThrows(SecurityException.class,()->fixture.verifyBindings(url,"migration","migration","consumer"));
        assertThrows(IllegalArgumentException.class,()->fixture(now.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->fixture(now.plusSeconds(3600)));
    }
    @Test void pocCloseProjectMustMatchTheEntireRunIdentity()throws Exception{
        var json=JsonMapper.builder().build();var value=(tools.jackson.databind.node.ObjectNode)json.readTree(Files.readAllBytes(file));
        value.put("projectId","agrosphere-poc-close-"+run.replace("-",""));
        Files.write(file,json.writeValueAsBytes(value));sha=hash(Files.readAllBytes(file));fixture(now).permit(estate,block);
        for(var project:List.of("agrosphere-poc-close-"+UUID.randomUUID().toString().replace("-",""),
            "agrosphere-poc-close-1234abcd","agrosphere-poc-close-"+run.replace("-","")+"-extra","existing-stack")){
            value.put("projectId",project);Files.write(file,json.writeValueAsBytes(value));sha=hash(Files.readAllBytes(file));
            assertThrows(IllegalArgumentException.class,()->fixture(now));
        }
    }
    @Test void changedManifestOrMarkerStopsAnAlreadyConstructedFixture()throws Exception{
        var fixture=fixture(now);Files.writeString(root.resolve(".generated-analytics-owner"),UUID.randomUUID().toString());
        assertThrows(SecurityException.class,fixture::verify);Files.writeString(root.resolve(".generated-analytics-owner"),run);
        Files.writeString(file," ",StandardOpenOption.APPEND);assertThrows(SecurityException.class,fixture::verify);
    }
    @Test void environmentalPrincipalQueueSchemaAndWindowMustBeExplicitlyBound()throws Exception{
        assertThrows(SecurityException.class,()->fixture(now).verifyEnvironmentalBindings("environment"));
        var json=JsonMapper.builder().build();var value=(tools.jackson.databind.node.ObjectNode)json.readTree(Files.readAllBytes(file));
        value.put("environmentRabbitUser","environment");value.put("environmentQueue","agrosphere.analytics.environment.v1");
        value.put("environmentSchemaSha256","78165fefbd980776ac44cb567e8fb213ac4fdd5ac979a4442e7e2d6d545647a6");
        value.put("environmentWindowFrom","2026-01-01T00:00:00Z");value.put("environmentWindowTo","2026-01-02T00:00:00Z");
        Files.write(file,json.writeValueAsBytes(value));sha=hash(Files.readAllBytes(file));fixture(now).verifyEnvironmentalBindings("environment");
        for(var key:List.of("environmentQueue","environmentSchemaSha256","environmentWindowFrom","environmentWindowTo")){
            var changed=value.deepCopy();changed.put(key,"changed");Files.write(file,json.writeValueAsBytes(changed));sha=hash(Files.readAllBytes(file));
            assertThrows(RuntimeException.class,()->fixture(now).verifyEnvironmentalBindings("environment"));
        }
        value.put("environmentRabbitUser","consumer");Files.write(file,json.writeValueAsBytes(value));sha=hash(Files.readAllBytes(file));
        assertThrows(SecurityException.class,()->fixture(now).verifyEnvironmentalBindings("consumer"));
        try(var stream=getClass().getResourceAsStream("/db/migration/V3__environmental_history.sql")){
            assertNotNull(stream);assertEquals("78165fefbd980776ac44cb567e8fb213ac4fdd5ac979a4442e7e2d6d545647a6",hash(stream.readAllBytes()));
        }
    }
}
