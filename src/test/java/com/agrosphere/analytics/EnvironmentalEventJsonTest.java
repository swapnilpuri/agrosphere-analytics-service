package com.agrosphere.analytics;

import com.agrosphere.analytics.adapter.in.messaging.EnvironmentalEventJson;
import com.agrosphere.analytics.adapter.in.web.EnvironmentalController;
import com.agrosphere.analytics.domain.EnvironmentalProjection;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class EnvironmentalEventJsonTest {
    static final String KEY="agrosphere.TelemetryObservationRecorded.v1";
    static final JsonMapper json=JsonMapper.builder().build();
    static final EnvironmentalEventJson parser=new EnvironmentalEventJson();
    static String id(long n) { return String.format("00000000-0000-4000-8000-%012d",n); }
    static ObjectNode event() {
        var e=json.createObjectNode();e.put("eventId",id(1));e.put("eventType","TelemetryObservationRecorded");e.put("eventVersion",1);
        e.put("timestamp","2026-01-01T00:00:01Z");e.put("source","agrosphere-telemetry-service");e.put("correlationId",id(8));
        e.put("estateId",id(2));e.put("blockId",id(3));e.put("dataProvenance","SIMULATED");e.put("aggregateId",id(4));e.put("aggregateVersion",1);
        var p=e.putObject("payload");p.put("schemaVersion",1);p.put("observationId",id(4));p.put("deviceId",id(5));p.put("estateId",id(2));p.put("blockId",id(3));
        p.put("dataProvenance","SIMULATED");p.put("observedAt","2026-01-01T00:00:00Z");p.put("receivedAt","2026-01-01T00:00:01Z");
        p.put("kind","AIR_TEMPERATURE");p.put("unit","CELSIUS");p.put("value","-2.125000");return e;
    }
    static byte[] bytes(ObjectNode e) { return json.writeValueAsBytes(e); }
    static com.agrosphere.analytics.domain.EnvironmentalObservation parse(ObjectNode e) { return parser.parse(bytes(e),KEY); }
    @Test void exactValuesKindsAndCanonicalFullEnvelopeFingerprint() {
        var e=event();var first=parse(e);assertEquals("-2.125000",first.value());
        var spaced=(" \n"+e.toString()+" \n").getBytes(StandardCharsets.UTF_8);assertEquals(first,parser.parse(spaced,KEY));
        var reverse=json.createObjectNode();var keys=new ArrayList<>(e.propertyNames());Collections.reverse(keys);for(var k:keys)reverse.set(k,e.get(k));assertEquals(first,parse(reverse));
        var numeric=e.toString().replace("\"eventVersion\":1,","\"eventVersion\":1e0,");assertEquals(first,parser.parse(numeric.getBytes(StandardCharsets.UTF_8),KEY));
        e.put("correlationId",id(99));assertNotEquals(first.fingerprint(),parse(e).fingerprint());
        var p=(ObjectNode)e.path("payload");p.put("kind","SOIL_MOISTURE");p.put("unit","PERCENT");p.put("value","0");assertEquals("0",parse(e).value());
        p.put("kind","INTERVAL_RAINFALL");p.put("unit","MILLIMETRE");p.put("value","0.000");p.put("intervalStart","2025-12-31T23:00:00Z");p.put("intervalEnd","2026-01-01T00:00:00Z");assertNotNull(parse(e).intervalStart());
    }
    @Test void everyRequiredFieldUnknownFieldsAndWireDiscriminatorsAreEnforced() {
        for(var key:event().propertyNames()){var bad=event();bad.remove(key);assertThrows(IllegalArgumentException.class,()->parse(bad),key);}
        for(var key:event().path("payload").propertyNames()){var bad=event();((ObjectNode)bad.path("payload")).remove(key);assertThrows(IllegalArgumentException.class,()->parse(bad),key);}
        for(var field:List.of("eventType","source","dataProvenance","aggregateId","estateId","blockId")){var bad=event();bad.put(field,id(999));assertThrows(IllegalArgumentException.class,()->parse(bad),field);}
        var bad=event();bad.put("eventVersion",2);assertThrows(IllegalArgumentException.class,()->parse(bad));
        bad.put("eventVersion",1);bad.put("aggregateVersion",2);assertThrows(IllegalArgumentException.class,()->parse(bad));
        var unknown=event();unknown.put("privateField","not allowed");assertThrows(IllegalArgumentException.class,()->parse(unknown));
        assertThrows(IllegalArgumentException.class,()->parser.parse(bytes(event()),"agrosphere.SensorReadingReceived.v1"));
    }
    @Test void exactValueUnitIntervalTimestampAndIdentitySemantics() {
        for(var value:List.of("NaN","1e0","01","1.0000001","1000000000000")){var e=event();((ObjectNode)e.path("payload")).put("value",value);assertThrows(IllegalArgumentException.class,()->parse(e));}
        for(var value:List.of("2026-02-30T00:00:00Z","2026-01-01T24:00:00Z","2026-01-01T00:00:60Z","2026-01-01T00:00:00.0001Z","2026-01-01T00:00:00+00:00")){
            var e=event();((ObjectNode)e.path("payload")).put("observedAt",value);assertThrows(IllegalArgumentException.class,()->parse(e));assertThrows(IllegalArgumentException.class,()->EnvironmentalController.utc(value));
        }
        for(var value:List.of("-1","100.000001")){var e=event();var p=(ObjectNode)e.path("payload");p.put("kind","SOIL_MOISTURE");p.put("unit","PERCENT");p.put("value",value);assertThrows(IllegalArgumentException.class,()->parse(e));}
        var e=event();((ObjectNode)e.path("payload")).put("observedAt","2026-01-01T00:05:02Z");assertThrows(IllegalArgumentException.class,()->parse(e));
        var wrong=event();((ObjectNode)wrong.path("payload")).put("unit","PERCENT");assertThrows(IllegalArgumentException.class,()->parse(wrong));
    }
    @Test void malformedUtf8UnicodeDuplicatesTrailingTokensAndFiniteAdmissionFailClosed() {
        for(var text:List.of("{} {}","\ufeff{}","{\"a\":1,\"a\":2}","{\"bad\":\"\\ud800\"}"))assertThrows(IllegalArgumentException.class,()->parser.parse(text.getBytes(StandardCharsets.UTF_8),KEY));
        assertThrows(IllegalArgumentException.class,()->parser.parse(new byte[]{(byte)0xc3,(byte)0x28},KEY));
        assertThrows(EnvironmentalEventJson.AdmissionLimit.class,()->parser.parse(new byte[262145],KEY));
        for(var raw:List.of("0e2147483647","1".repeat(1001)))assertThrows(EnvironmentalEventJson.AdmissionLimit.class,()->parser.parse(("{\"value\":"+raw+"}").getBytes(StandardCharsets.UTF_8),KEY));
        assertThrows(EnvironmentalEventJson.AdmissionLimit.class,()->parser.parse(("[".repeat(65)+"0"+"]".repeat(65)).getBytes(StandardCharsets.UTF_8),KEY));
        assertThrows(EnvironmentalEventJson.AdmissionLimit.class,()->parser.parse(("["+"0,".repeat(10000)+"0]").getBytes(StandardCharsets.UTF_8),KEY));
        var text=event().toString();var padded=text+" ".repeat(262144-text.getBytes(StandardCharsets.UTF_8).length);assertEquals(parse(event()),parser.parse(padded.getBytes(StandardCharsets.UTF_8),KEY));
    }
    @Test void returnedPureViewMatchesExactPinnedResponseSchemaAndExcludesFingerprint() {
        var fact=parse(event());var at=Instant.parse("2026-01-01T01:00:00Z");var state=EnvironmentalProjection.apply(EnvironmentalProjection.State.empty(),fact,at).state();
        var q=new EnvironmentalProjection.Query(fact.estateId(),fact.blockId(),Instant.parse("2026-01-01T00:00:00Z"),at);
        var response=EnvironmentalController.view(q,EnvironmentalProjection.rebuild(state,q),at);var raw=json.writeValueAsBytes(response);parser.validateResponse(raw);
        assertFalse(new String(raw,StandardCharsets.UTF_8).contains("fingerprint"));
    }
}
