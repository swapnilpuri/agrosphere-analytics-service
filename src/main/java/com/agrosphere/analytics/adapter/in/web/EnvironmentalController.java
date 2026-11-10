package com.agrosphere.analytics.adapter.in.web;

import com.agrosphere.analytics.*;
import com.agrosphere.analytics.adapter.in.messaging.EnvironmentalEventJson;
import com.agrosphere.analytics.adapter.out.persistence.EnvironmentalJdbc;
import com.agrosphere.analytics.adapter.out.web.EstateClient;
import com.agrosphere.analytics.application.ReadEnvironmentalHistory;
import com.agrosphere.analytics.domain.EnvironmentalProjection.*;
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
public class EnvironmentalController {
    private final ObjectProvider<EnvironmentalJdbc> store;
    private final ObjectProvider<GeneratedAnalytics> fixture;
    private final ObjectProvider<EstateClient> estate;
    public EnvironmentalController(ObjectProvider<EnvironmentalJdbc> store,ObjectProvider<GeneratedAnalytics> fixture,ObjectProvider<EstateClient> estate) { this.store=store;this.fixture=fixture;this.estate=estate; }
    @GetMapping("/api/v1/analytics/estates/{estateId}/blocks/{blockId}/environment/observations")
    public Map<String,Object> history(@PathVariable String estateId,@PathVariable String blockId,@RequestParam MultiValueMap<String,String> parameters,@AuthenticationPrincipal Jwt jwt) {
        UUID e=canonical(estateId),b=canonical(blockId);
        if(!parameters.keySet().equals(Set.of("from","to"))||parameters.values().stream().anyMatch(x->x.size()!=1))throw new IllegalArgumentException();
        var q=new Query(e,b,utc(parameters.getFirst("from")),utc(parameters.getFirst("to")));
        Set<String> roles=new HashSet<>();Set<UUID> scopes=new HashSet<>();
        if(jwt.getClaims().get("realm_access") instanceof Map<?,?> values && values.get("roles") instanceof Collection<?> list)for(var r:list)if(r instanceof String value)roles.add(value);
        if(jwt.getClaims().get("estate_ids") instanceof Collection<?> list)for(var id:list){if(!(id instanceof String value))throw new ResponseStatusException(HttpStatus.FORBIDDEN);scopes.add(canonical(value));}
        var reader=new ReadEnvironmentalHistory(query->{
            if(store.getIfAvailable()==null||fixture.getIfAvailable()==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Environmental history disabled");
            try{fixture.getObject().permit(e,b);}catch(SecurityException expired){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Environmental fixture unavailable");}
            estate.getObject().active(e,b,jwt.getTokenValue());
        },query->store.getObject().read(query));
        try{var result=view(q,reader.read(q,roles,scopes),Instant.now().truncatedTo(ChronoUnit.MILLIS));result.put("from",parameters.getFirst("from"));result.put("to",parameters.getFirst("to"));return result;}
        catch(SecurityException denied){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Environmental history denied");}
    }
    private static UUID canonical(String value) { var id=EnvironmentalEventJson.id(value);if(!id.toString().equals(value))throw new IllegalArgumentException();return id; }
    public static Instant utc(String value) {
        if(value==null||!value.matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,3})?Z"))throw new IllegalArgumentException();
        try{return EnvironmentalEventJson.time(value);}catch(java.time.DateTimeException invalid){throw new IllegalArgumentException("Invalid UTC instant");}
    }
    private static Map<String,Object> fields(Object... pairs) { var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result; }
    private static Object time(Instant value) { return value==null?null:value.toString(); }
    public static Map<String,Object> view(Query q,View v,Instant now) {
        var observations=v.observations().stream().map(f->{
            var row=fields("observationId",f.observationId().toString(),"deviceId",f.deviceId().toString(),"observedAt",f.observedAt().toString(),"receivedAt",f.receivedAt().toString(),"kind",f.kind().name(),"unit",f.unit(),"value",f.value(),"sourceEventId",f.eventId().toString(),"sourceEventAt",f.receivedAt().toString());
            if(f.intervalStart()!=null){row.put("intervalStart",f.intervalStart().toString());row.put("intervalEnd",f.intervalEnd().toString());}return row;
        }).toList();
        return fields("estateId",q.estateId().toString(),"blockId",q.blockId().toString(),"from",q.from().toString(),"to",q.to().toString(),"generatedAt",now.toString(),"dataProvenance","SIMULATED","availability",v.availability(),"projectionState",v.projectionState(),"totalMatchingCount",v.totalMatchingCount().toString(),"truncated",v.truncated(),"returnedKindCounts",v.returnedKindCounts(),"conflictCount",v.conflictCount(),"lastProcessedAt",time(v.lastProcessedAt()),"latestSourceEventAt",time(v.latestSourceEventAt()),"observations",observations);
    }
}
