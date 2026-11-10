package com.agrosphere.analytics;

import com.agrosphere.analytics.adapter.in.messaging.CollectionEventJson;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.json.JsonMapper;

class CollectionEventJsonTest {
    final CollectionEventJson parser=new CollectionEventJson();
    byte[] fixture(String type)throws Exception {return getClass().getResourceAsStream("/collection-events/"+type+".json").readAllBytes();}
    String key(String type){return "agrosphere."+type+".v2";}
    @Test void allFourIndependentFullEnvelopesMatchThePinnedSchemas()throws Exception {
        for(var type:List.of("LatexCollectionRecorded","DrcMeasurementRecorded","LatexCollectionAdjusted","LatexCollectionVoided")) {
            var r=parser.parse(fixture(type),key(type));assertNotNull(r);
            // Independent ANL_JSON_DECIMAL_V1 golden values; retained input fixtures, no numeric float conversion.
            assertEquals(Map.of("DrcMeasurementRecorded","94104752c29949466b690eddc43b0d4fb20352d4a5c91518b754617bbed68d1f","LatexCollectionAdjusted","f5de540097a2db50f8f7a5d315dccc87fd5409a1cfcb13761a90d2a3d89f768c","LatexCollectionRecorded","3e1cf81c34b95cea506474e2198ea3a2474d3afaf6363b85fdae2701a5a17a87","LatexCollectionVoided","be3e0ea90b05a527acf546c046595ae751259860d44a403bdb4e5dd21da9d523").get(type),r.fingerprint());
        }
    }
    @Test void semanticNumberAndObjectOrderDuplicatesHaveStableFullFingerprint()throws Exception {
        String type="LatexCollectionRecorded";var original=fixture(type);
        var json=JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        var tree=json.readTree(original);var reordered=json.createObjectNode();var keys=new ArrayList<>(tree.propertyNames());Collections.reverse(keys);for(var k:keys)reordered.set(k,tree.path(k));
        assertEquals(parser.parse(original,key(type)).fingerprint(),parser.parse(json.writeValueAsBytes(reordered),key(type)).fingerprint());
        var changed=new String(original,StandardCharsets.UTF_8).replace("\"wetLatexKg\": 125.5", "\"wetLatexKg\": 125.500");
        assertEquals(parser.parse(original,key(type)).fingerprint(),parser.parse(changed.getBytes(StandardCharsets.UTF_8),key(type)).fingerprint());
    }
    @Test void changingNonretainedPersonalFieldsIsStillAConflictFingerprint()throws Exception {
        String type="LatexCollectionRecorded";var original=fixture(type);var changed=new String(original,StandardCharsets.UTF_8).replace("SIMULATED-WORKER","OTHER-SIMULATED-WORKER").getBytes(StandardCharsets.UTF_8);
        var a=parser.parse(original,key(type));var b=parser.parse(changed,key(type));assertEquals(a.collectionId(),b.collectionId());assertNotEquals(a.fingerprint(),b.fingerprint());
    }
    @Test void routingMalformedUtf8DuplicateKeysAndUnknownFieldsFailWithoutBodyLeak()throws Exception {
        String type="LatexCollectionRecorded";var valid=fixture(type);
        for(var bytes:List.of(new byte[]{(byte)0xc0,(byte)0xaf},"{} {}".getBytes(StandardCharsets.UTF_8),new String(valid,StandardCharsets.UTF_8).replaceFirst("\\{","{\"extra\":0,").getBytes(StandardCharsets.UTF_8),new String(valid,StandardCharsets.UTF_8).replaceFirst("\\{","{\"eventVersion\":2,").getBytes(StandardCharsets.UTF_8))) {
            var error=assertThrows(IllegalArgumentException.class,()->parser.parse(bytes,key(type)));assertEquals("Invalid collection-v2 envelope",error.getMessage());
        }
        assertThrows(IllegalArgumentException.class,()->parser.parse(valid,"agrosphere.LatexCollectionRecorded.v1"));
    }
    byte[] encoded(String text){return text.getBytes(StandardCharsets.UTF_8);}
    String recorded()throws Exception{return new String(fixture("LatexCollectionRecorded"),StandardCharsets.UTF_8);}
    @Test void exactByteLimitPreservesFingerprintAndOversizedSchemaValidTimestampIsAdmissionOnly()throws Exception {
        var original=recorded();var originalBytes=encoded(original);
        var padded=encoded(original+" ".repeat(CollectionEventJson.MAX_BYTES-originalBytes.length));
        assertEquals(CollectionEventJson.MAX_BYTES,padded.length);
        assertEquals(parser.parse(originalBytes,key("LatexCollectionRecorded")).fingerprint(),parser.parse(padded,key("LatexCollectionRecorded")).fingerprint());
        assertThrows(CollectionEventJson.AdmissionLimit.class,()->parser.parse(encoded(new String(padded,StandardCharsets.UTF_8)+" "),key("LatexCollectionRecorded")));
        var adjusted=new String(fixture("LatexCollectionAdjusted"),StandardCharsets.UTF_8);
        var oversized=adjusted.replace("\"timestamp\": \"2026-10-27T14:00:00Z\"","\"timestamp\": \"2026-10-27T14:00:00."+"0".repeat(262145)+"Z\"");
        assertTrue(oversized.length()>CollectionEventJson.MAX_BYTES);
        var tree=JsonMapper.builder().build().readTree(oversized);
        assertEquals(CollectionEventJson.time(tree.path("payload").path("collection").path("updatedAt").asText()),CollectionEventJson.time(tree.path("timestamp").asText()));
        var failure=assertThrows(CollectionEventJson.AdmissionLimit.class,()->parser.parse(encoded(oversized),key("LatexCollectionAdjusted")));
        assertEquals("Collection-v2 admission limit",failure.getMessage());
    }
    @Test void numericLexemesAreBoundedBeforeArithmeticWithoutChangingAcceptedFingerprint()throws Exception {
        var original=recorded();var base=parser.parse(encoded(original),key("LatexCollectionRecorded")).fingerprint();
        var exact=original.replace("\"wetLatexKg\": 125.5","\"wetLatexKg\": 125.5"+"0".repeat(995));
        assertNotEquals(original,exact);assertEquals(base,parser.parse(encoded(exact),key("LatexCollectionRecorded")).fingerprint());
        var over=original.replace("\"wetLatexKg\": 125.5","\"wetLatexKg\": 125.5"+"0".repeat(996));
        assertThrows(CollectionEventJson.AdmissionLimit.class,()->parser.parse(encoded(over),key("LatexCollectionRecorded")));
        for(var spelling:List.of("0e1000","-0e-1000","0e+00001000","-0.000")){
            var value=original.replace("\"estateVersionSnapshot\": 0","\"estateVersionSnapshot\": "+spelling);
            assertNotEquals(original,value);assertEquals(base,parser.parse(encoded(value),key("LatexCollectionRecorded")).fingerprint(),spelling);
        }
        for(var spelling:List.of("0e1001","-0e-1001","1e999999999","1e-999999999","0e2147483647")){
            var value=original.replace("\"estateVersionSnapshot\": 0","\"estateVersionSnapshot\": "+spelling);
            assertThrows(CollectionEventJson.AdmissionLimit.class,()->parser.parse(encoded(value),key("LatexCollectionRecorded")),spelling);
        }
        var negative=original.replace("\"wetLatexKg\": 125.5","\"wetLatexKg\": -125.5");
        var invalid=assertThrows(IllegalArgumentException.class,()->parser.parse(encoded(negative),key("LatexCollectionRecorded")));
        assertFalse(invalid instanceof CollectionEventJson.AdmissionLimit);
    }
    @Test void depthAndMemberCeilingsAreAdmissionFailuresDistinctFromSchemaFailure(){
        for(int depth:List.of(64,65)){
            var value=encoded("[".repeat(depth)+"0"+"]".repeat(depth));
            var failure=assertThrows(IllegalArgumentException.class,()->parser.parse(value,key("LatexCollectionRecorded")));
            assertEquals(depth>64,failure instanceof CollectionEventJson.AdmissionLimit);
        }
        for(int size:List.of(10000,10001)){
            var value=encoded("["+"0,".repeat(size-1)+"0]");
            var failure=assertThrows(IllegalArgumentException.class,()->parser.parse(value,key("LatexCollectionRecorded")));
            assertEquals(size>10000,failure instanceof CollectionEventJson.AdmissionLimit);
        }
    }
    @Test void fullAdjustedAndVoidWithMaximumBoundedEvidenceStringsFitBudgets()throws Exception {
        var json=JsonMapper.builder().build();
        for(var type:List.of("LatexCollectionAdjusted","LatexCollectionVoided")) {
            var tree=json.readTree(fixture(type));
            var c=(tools.jackson.databind.node.ObjectNode)tree.path("payload").path("collection");
            String evidence="\uD83C\uDF33".repeat(200),reason="\uD83C\uDF33".repeat(2000);
            for(var key:List.of("workerId","createdBy","updatedBy"))c.put(key,evidence);
            c.put("collectionReference","A".repeat(64));c.put("backdateReason",reason);
            var weight=(tools.jackson.databind.node.ObjectNode)c.path("weightEvidence");
            weight.put("sourceReference",evidence);weight.put("instrumentReference",evidence);
            var measurement=(tools.jackson.databind.node.ObjectNode)c.path("currentMeasurement");
            for(var key:List.of("sourceReference","sampleReference","instrumentReference","recordedBy"))measurement.put(key,evidence);
            var adjustment=(tools.jackson.databind.node.ObjectNode)tree.path("payload").path("adjustment");
            adjustment.put("reason",reason);adjustment.put("approvedBy",evidence);
            var bytes=json.writeValueAsBytes(tree);assertTrue(bytes.length>15000);assertTrue(bytes.length<CollectionEventJson.MAX_BYTES);
            assertNotNull(parser.parse(bytes,key(type)));
        }
    }
    @Test void invalidDerivedMassIdentityAndDatePartitionAreRejected()throws Exception {
        String type="DrcMeasurementRecorded";
        var json=JsonMapper.builder().build();
        for(var field:List.of("dryRubberKg","collectionDate","estateId")) {
            var tree=json.readTree(fixture(type));
            var collection=(tools.jackson.databind.node.ObjectNode)tree.path("payload").path("collection");
            switch(field) {
              case "dryRubberKg" -> collection.put(field, new java.math.BigDecimal("40.663"));
              case "collectionDate" -> collection.put(field,"2026-10-28");
              default -> collection.put(field,UUID.randomUUID().toString());
            }
            assertThrows(IllegalArgumentException.class,()->parser.parse(json.writeValueAsBytes(tree),key(type)),field);
        }
    }
}
