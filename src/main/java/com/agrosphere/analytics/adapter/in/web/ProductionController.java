package com.agrosphere.analytics.adapter.in.web;

import com.agrosphere.analytics.*;
import com.agrosphere.analytics.adapter.in.messaging.CollectionEventJson;
import com.agrosphere.analytics.adapter.out.persistence.ProductionJdbc;
import com.agrosphere.analytics.adapter.out.web.EstateClient;
import com.agrosphere.analytics.application.ReadProductionHistory;
import com.agrosphere.analytics.domain.ProductionProjection.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
public class ProductionController {
    private final ObjectProvider<ProductionJdbc> store;private final ObjectProvider<GeneratedAnalytics> fixture;private final ObjectProvider<EstateClient> estate;
    public ProductionController(ObjectProvider<ProductionJdbc> store,ObjectProvider<GeneratedAnalytics> fixture,ObjectProvider<EstateClient> estate){this.store=store;this.fixture=fixture;this.estate=estate;}
    @GetMapping("/api/v1/analytics/estates/{estateId}/blocks/{blockId}/production/daily")
    public Map<String,Object> history(@PathVariable String estateId,@PathVariable String blockId,@RequestParam MultiValueMap<String,String> parameters,@AuthenticationPrincipal Jwt jwt){
        UUID e=canonical(estateId),b=canonical(blockId);
        if(!parameters.keySet().equals(Set.of("from","to"))||parameters.values().stream().anyMatch(x->x.size()!=1))throw new IllegalArgumentException();
        var q=new Query(e,b,LocalDate.parse(parameters.getFirst("from")),LocalDate.parse(parameters.getFirst("to")));
        Set<String> roles=new HashSet<>();Set<UUID> scopes=new HashSet<>();
        Object access=jwt.getClaims().get("realm_access");if(access instanceof Map<?,?> values&&values.get("roles") instanceof Collection<?> list)for(var r:list)if(r instanceof String value)roles.add(value);
        if(jwt.getClaims().get("estate_ids") instanceof Collection<?> list)for(var id:list){if(!(id instanceof String value))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Production history denied");scopes.add(canonical(value));}
        var service=new ReadProductionHistory(query->{
            if(fixture.getIfAvailable()==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Generated Analytics is disabled");
            try{fixture.getObject().verify();}catch(SecurityException expired){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Generated Analytics unavailable");}estate.getObject().active(e,b,jwt.getTokenValue());
        },query->store.getObject().read(query));
        View v;try{v=service.read(q,roles,scopes);}catch(SecurityException denied){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Production history denied");}
        return view(q,v,Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
    private static UUID canonical(String value){UUID result=CollectionEventJson.id(value);if(!result.toString().equals(value))throw new IllegalArgumentException();return result;}
    private static Map<String,Object> fields(Object... pairs){var result=new LinkedHashMap<String,Object>();for(int n=0;n<pairs.length;n+=2)result.put((String)pairs[n],pairs[n+1]);return result;}
    private static Object time(Instant value){return value==null?null:value.toString();}
    public static Map<String,Object> view(Query q,View v,Instant now){
        var days=v.days().stream().map(day->{var m=day.metrics();Object metrics=m==null?null:fields("activeCollectionCount",m.activeCollectionCount(),"pendingDrcCount",m.pendingDrcCount(),"calculatedCollectionCount",m.calculatedCollectionCount(),"voidedCollectionCount",m.voidedCollectionCount(),"totalWetLatexKg",m.totalWetLatexKg().toPlainString(),"pendingWetLatexKg",m.pendingWetLatexKg().toPlainString(),"calculatedWetLatexKg",m.calculatedWetLatexKg().toPlainString(),"knownDryRubberKg",m.knownDryRubberKg()==null?null:m.knownDryRubberKg().toPlainString(),"isComplete",m.isComplete());return fields("date",day.date().toString(),"availability",m==null?"NONE":"OBSERVED","metrics",metrics);}).toList();
        return fields("estateId",q.estateId().toString(),"blockId",q.blockId().toString(),"from",q.from().toString(),"to",q.to().toString(),"generatedAt",now.toString(),"dataProvenance","SIMULATED","aggregationPolicy","SUM_ROUNDED_COLLECTION_KG_V1","projectionState",v.missingSourceVersionCount().signum()>0||v.conflictCount()>0||v.unsupportedCount()>0?"INCOMPLETE":"OBSERVED_ONLY","lastProcessedAt",time(v.lastProcessedAt()),"latestSourceEventAt",time(v.latestSourceEventAt()),"missingSourceVersionCount",v.missingSourceVersionCount().toString(),"conflictCount",v.conflictCount(),"unsupportedCount",v.unsupportedCount(),"sourceVersionSample",v.sourceVersionSample().stream().map(x->fields("collectionId",x.collectionId().toString(),"aggregateVersion",x.aggregateVersion(),"sourceEventId",x.sourceEventId().toString(),"sourceEventAt",x.sourceEventAt().toString(),"collectionDate",x.collectionDate().toString())).toList(),"sourceVersionSampleTruncated",v.sourceVersionSampleTruncated(),"days",days);
    }
}
