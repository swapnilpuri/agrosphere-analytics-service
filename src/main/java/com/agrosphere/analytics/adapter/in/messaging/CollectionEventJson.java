package com.agrosphere.analytics.adapter.in.messaging;

import com.agrosphere.analytics.domain.ProductionRevision;
import static com.agrosphere.analytics.domain.ProductionRevision.*;
import java.io.*;
import java.math.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Offline evaluation of the reviewed pinned schema vocabulary, with exact decimal arithmetic. */
public final class CollectionEventJson {
    public static final int MAX_BYTES=262144;
    public static final int MAX_DEPTH=64,MAX_MEMBERS=10000,MAX_NUMBER_LENGTH=1000,MAX_EXPONENT=1000;
    /** Finite generated transport profile, not a public schema-validation failure. */
    public static final class AdmissionLimit extends IllegalArgumentException {
        private AdmissionLimit(){super("Collection-v2 admission limit");}
    }
    private static final Map<String,Kind> KINDS=Map.of("LatexCollectionRecorded",Kind.RECORDED,"DrcMeasurementRecorded",Kind.DRC_RECORDED,"LatexCollectionAdjusted",Kind.ADJUSTED,"LatexCollectionVoided",Kind.VOIDED);
    private final ObjectMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH).maxNumberLength(MAX_NUMBER_LENGTH).maxStringLength(MAX_BYTES).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    private final Map<String,JsonNode> schemas=new HashMap<>();
    public CollectionEventJson() {
        for(var name:KINDS.keySet())try(var stream=getClass().getResourceAsStream("/contracts/events/operations/v2/"+name+".schema.json")) {
            if(stream==null)throw new IllegalStateException("Missing independent contract resource");schemas.put(name,json.readTree(stream));
        }catch(IOException e){throw new IllegalStateException("Unreadable pinned schema");}
    }
    public ProductionRevision parse(byte[] bytes,String routingKey) {
        try {
            if(bytes.length>MAX_BYTES)throw new AdmissionLimit();
            if(bytes.length==0)throw new Bad();
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(text.charAt(0)=='\ufeff')throw new Bad();
            admission(text); // inspect raw numeric tokens before any BigDecimal conversion/arithmetic
            var e=json.readTree(text);budget(e,new int[]{0},0);
            var name=e.path("eventType").asText();if(!KINDS.containsKey(name)||!routingKey.equals("agrosphere."+name+".v2"))throw new Bad();
            validate(schemas.get(name),e,schemas.get(name));semantics(e);
            var c=e.path("payload").path("collection");
            return new ProductionRevision(id(e.path("eventId").asText()),fingerprint(e),KINDS.get(name),time(e.path("timestamp").asText()),
                id(c.path("id").asText()),c.path("version").decimalValue().longValueExact(),id(c.path("estateId").asText()),id(c.path("blockId").asText()),
                LocalDate.parse(c.path("collectionDate").asText()),ZoneId.of(c.path("estateTimezone").asText()),Provenance.valueOf(c.path("dataProvenance").asText()),
                Status.valueOf(c.path("status").asText()),c.path("wetLatexKg").decimalValue(),c.path("dryRubberKg").isNull()?null:c.path("dryRubberKg").decimalValue());
        } catch(AdmissionLimit e) { throw e;
        } catch(tools.jackson.core.exc.StreamConstraintsException e) { throw new AdmissionLimit();
        } catch(Exception e) { throw new IllegalArgumentException("Invalid collection-v2 envelope"); }
    }
    private void admission(String text) {
        try(var parser=json.createParser(text)) {
            var arrays=new ArrayDeque<Boolean>();int members=0;
            for(JsonToken token;(token=parser.nextToken())!=null;) {
                if(token==JsonToken.PROPERTY_NAME||(!token.isStructEnd()&&token!=JsonToken.PROPERTY_NAME&&!arrays.isEmpty()&&arrays.peek())) {
                    if(++members>MAX_MEMBERS)throw new AdmissionLimit();
                }
                if(token.isStructStart()){arrays.push(token==JsonToken.START_ARRAY);if(arrays.size()>MAX_DEPTH)throw new AdmissionLimit();}
                else if(token.isStructEnd())arrays.pop();
                if(token.isNumeric()) {
                    String raw=parser.getString();if(raw.length()>MAX_NUMBER_LENGTH)throw new AdmissionLimit();
                    int e=Math.max(raw.indexOf('e'),raw.indexOf('E'));
                    if(e>=0) {
                        int at=e+1;if(raw.charAt(at)=='+'||raw.charAt(at)=='-')at++;
                        int magnitude=0;
                        for(;at<raw.length();at++){magnitude=magnitude*10+(raw.charAt(at)-'0');if(magnitude>MAX_EXPONENT)throw new AdmissionLimit();}
                    }
                }
            }
        }
    }
    public void validateResponse(byte[] bytes){try(var stream=getClass().getResourceAsStream("/contracts/openapi/analytics-service.yaml")){
        var api=json.readTree(stream);var schema=api.path("components").path("schemas").path("DailyProductionHistory");var value=json.readTree(bytes);validate(schema,value,schema);
    }catch(Exception e){throw new IllegalArgumentException("Invalid Analytics response");}}
    private void budget(JsonNode n,int[] members,int depth){
        if(depth>MAX_DEPTH)throw new AdmissionLimit();
        if(n.isTextual()){
            var t=n.asText();for(int p=0;p<t.length();p++){char c=t.charAt(p);if(Character.isHighSurrogate(c)){if(++p>=t.length()||!Character.isLowSurrogate(t.charAt(p)))throw new Bad();}else if(Character.isLowSurrogate(c))throw new Bad();}
        }
        if(n.isObject()||n.isArray()){members[0]+=n.size();if(members[0]>MAX_MEMBERS)throw new AdmissionLimit();for(var child:n)budget(child,members,depth+1);}
    }
    private boolean valid(JsonNode schema,JsonNode n,JsonNode root){try{validate(schema,n,root);return true;}catch(Bad e){return false;}}
    private void validate(JsonNode s,JsonNode n,JsonNode root){
        if(s.has("$ref")){var ref=s.path("$ref").asText();if(!ref.startsWith("#/$defs/"))throw new Bad();validate(root.path("$defs").path(ref.substring(8)),n,root);}
        if(s.has("allOf"))for(var x:s.path("allOf"))validate(x,n,root);
        if(s.has("anyOf")){boolean ok=false;for(var x:s.path("anyOf"))ok|=valid(x,n,root);if(!ok)throw new Bad();}
        if(s.has("not")&&valid(s.path("not"),n,root))throw new Bad();
        if(s.has("if")&&valid(s.path("if"),n,root)&&s.has("then"))validate(s.path("then"),n,root);
        if(s.has("const")&&!same(s.path("const"),n))throw new Bad();
        if(s.has("enum")){boolean ok=false;for(var x:s.path("enum"))ok|=same(x,n);if(!ok)throw new Bad();}
        if(s.has("type")){
            boolean ok=switch(s.path("type").asText()){
                case "object"->n.isObject();case "array"->n.isArray();case "string"->n.isTextual();case "boolean"->n.isBoolean();case "null"->n.isNull();case "number"->n.isNumber();
                case "integer"->n.isNumber()&&n.decimalValue().stripTrailingZeros().scale()<=0;default->false;};if(!ok)throw new Bad();
        }
        if(n.isObject()){
            for(var required:s.path("required"))if(!n.has(required.asText()))throw new Bad();
            for(var key:n.propertyNames()){
                if(s.path("properties").has(key))validate(s.path("properties").path(key),n.path(key),root);
                else if(s.has("additionalProperties")&&s.path("additionalProperties").isBoolean()&&!s.path("additionalProperties").asBoolean())throw new Bad();
            }
        }
        if(n.isArray()){if(s.has("minItems")&&n.size()<s.path("minItems").asInt()||s.has("maxItems")&&n.size()>s.path("maxItems").asInt())throw new Bad();if(s.has("items"))for(var child:n)validate(s.path("items"),child,root);}
        if(n.isTextual()){
            var v=n.asText();int size=v.codePointCount(0,v.length());
            if(s.has("minLength")&&size<s.path("minLength").asInt()||s.has("maxLength")&&size>s.path("maxLength").asInt())throw new Bad();
            if(s.has("pattern")&&!Pattern.compile(s.path("pattern").asText()).matcher(v).find())throw new Bad();
            if(s.has("format"))try{switch(s.path("format").asText()){
                case "uuid"->id(v);case "date"->{var d=LocalDate.parse(v);if(d.getYear()<1||d.getYear()>9999)throw new Bad();}case "date-time"->time(v);default->throw new Bad();
            }}catch(Exception e){throw new Bad();}
        }
        if(n.isNumber()){
            var v=n.decimalValue();if(s.has("minimum")&&v.compareTo(s.path("minimum").decimalValue())<0||s.has("maximum")&&v.compareTo(s.path("maximum").decimalValue())>0)throw new Bad();
            if(s.has("multipleOf")&&v.remainder(s.path("multipleOf").decimalValue()).signum()!=0)throw new Bad();
        }
    }
    private static boolean same(JsonNode a,JsonNode b){return a.isNumber()&&b.isNumber()?a.decimalValue().compareTo(b.decimalValue())==0:a.equals(b);}
    public static UUID id(String value){if(!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new Bad();return UUID.fromString(value);}
    public static Instant time(String value){
        if(!value.matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6}0*)?Z")||value.contains(":60"))throw new Bad();
        var normalized=value.contains(".")?value.replaceAll("0+Z$","Z").replace(".Z","Z"):value;var result=Instant.parse(normalized);if(result.atOffset(ZoneOffset.UTC).getYear()<1)throw new Bad();return result;
    }
    private static void require(boolean value){if(!value)throw new Bad();}
    private static void eq(JsonNode a,JsonNode b){require(same(a,b));}
    private void semantics(JsonNode e){
        var p=e.path("payload");var c=p.path("collection");
        for(var pair:List.of(new String[]{"aggregateId","id"},new String[]{"aggregateVersion","version"},new String[]{"estateId","estateId"},new String[]{"blockId","blockId"},new String[]{"dataProvenance","dataProvenance"}))eq(e.path(pair[0]),c.path(pair[1]));
        var updated=time(c.path("updatedAt").asText());require(time(e.path("timestamp").asText()).equals(updated));
        require(!time(c.path("createdAt").asText()).isAfter(updated)&&!time(c.path("masterValidatedAt").asText()).isAfter(time(c.path("createdAt").asText())));
        var collected=time(c.path("collectedAt").asText());require(!collected.isAfter(updated));
        var day=LocalDate.parse(c.path("collectionDate").asText());require(day.equals(collected.atZone(ZoneId.of(c.path("estateTimezone").asText())).toLocalDate())&&!day.isBefore(LocalDate.parse(c.path("tappingDate").asText())));
        var m=c.path("currentMeasurement");
        if(!m.isNull()){
            eq(m.path("collectionId"),c.path("id"));eq(m.path("dataProvenance"),c.path("dataProvenance"));require(m.path("introducedVersion").decimalValue().compareTo(c.path("version").decimalValue())<=0);
            require(!collected.isAfter(time(m.path("measuredAt").asText()))&&!time(m.path("measuredAt").asText()).isAfter(time(m.path("recordedAt").asText()))&&!time(m.path("recordedAt").asText()).isAfter(updated));
            var exact=c.path("wetLatexKg").decimalValue().multiply(m.path("drcPercent").decimalValue()).movePointLeft(2);
            require(exact.compareTo(c.path("dryRubberExactKg").decimalValue())==0&&exact.setScale(3,RoundingMode.HALF_UP).compareTo(c.path("dryRubberKg").decimalValue())==0);
        }
        var kind=KINDS.get(e.path("eventType").asText());
        if(kind==Kind.RECORDED){eq(c.path("createdAt"),c.path("updatedAt"));eq(p.path("reason"),c.path("backdateReason"));}
        else if(kind==Kind.DRC_RECORDED){eq(p.path("measurementId"),m.path("id"));eq(m.path("introducedVersion"),c.path("version"));}
        else {
            var v=c.path("version").decimalValue();var adjustment=p.path("adjustment");
            require(p.path("previousVersion").decimalValue().compareTo(v.subtract(BigDecimal.ONE))==0);eq(p.path("previousVersion"),adjustment.path("beforeVersion"));eq(c.path("version"),adjustment.path("afterVersion"));
            eq(adjustment.path("approvedBy"),c.path("updatedBy"));eq(adjustment.path("approvedAt"),c.path("updatedAt"));eq(adjustment.path("correlationId"),e.path("correlationId"));
        }
    }
    private static void emit(ByteArrayOutputStream out,String value){out.writeBytes(value.getBytes(StandardCharsets.UTF_8));}
    private static void canonical(JsonNode n,ByteArrayOutputStream out){
        if(n.isNull())emit(out,"Z");else if(n.isBoolean())emit(out,n.asBoolean()?"T":"F");
        else if(n.isNumber())emit(out,"N"+(n.decimalValue().signum()==0?"0":n.decimalValue().stripTrailingZeros().toPlainString())+";");
        else if(n.isTextual()){byte[] bytes=n.asText().getBytes(StandardCharsets.UTF_8);emit(out,"S"+bytes.length+":");out.writeBytes(bytes);}
        else if(n.isArray()){emit(out,"A"+n.size()+":");for(var c:n)canonical(c,out);}
        else if(n.isObject()){var keys=new ArrayList<>(n.propertyNames());Collections.sort(keys);emit(out,"O"+keys.size()+":");for(var key:keys){byte[] bytes=key.getBytes(StandardCharsets.UTF_8);emit(out,"S"+bytes.length+":");out.writeBytes(bytes);canonical(n.path(key),out);}}
        else throw new Bad();
    }
    private static String fingerprint(JsonNode n){try{var out=new ByteArrayOutputStream();canonical(n,out);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static final class Bad extends IllegalArgumentException {}
}
