package com.agrosphere.analytics;

import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import com.agrosphere.analytics.adapter.in.messaging.CollectionEventJson;
import com.agrosphere.analytics.domain.ProductionProjection.Scope;

/** Rechecked short-lived owned fixture. No discovery of private config or existing targets. */
public final class GeneratedAnalytics {
    private final Path file,root;private final String digest;private final Clock clock;private final JsonNode config;private final Set<Scope> scopes;
    public GeneratedAnalytics(Path path,String digest,Clock clock){
        try{
            this.file=path.toRealPath();this.root=file.getParent();this.digest=digest;this.clock=clock;
            config=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(read(file,16384));
            var run=CollectionEventJson.id(config.path("runId").asText());if(!run.toString().equals(config.path("runId").asText()))throw new IllegalArgumentException();
            var project=config.path("projectId").asText();
            if(!project.matches("agrosphere-3a-check-[0-9a-f]{8}") && !project.equals("agrosphere-poc-close-"+run.toString().replace("-","")))throw new IllegalArgumentException();
            scopes=new HashSet<>();if(!config.path("scopes").isArray()||config.path("scopes").isEmpty()||config.path("scopes").size()>100)throw new IllegalArgumentException();
            for(var scope:config.path("scopes"))scopes.add(new Scope(CollectionEventJson.id(scope.path("estateId").asText()),CollectionEventJson.id(scope.path("blockId").asText())));
            verify();
        }catch(Exception e){throw new IllegalArgumentException("Unverified generated Analytics fixture");}
    }
    private static byte[] read(Path p,int max)throws Exception{try(var stream=Files.newInputStream(p)){var value=stream.readNBytes(max+1);if(value.length>max)throw new IllegalArgumentException();return value;}}
    private static String sha(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    public void verify(){try{
        if(!digest.matches("[0-9a-f]{64}")||!sha(read(file,16384)).equals(digest))throw new IllegalArgumentException();
        var marker=root.resolve(".generated-analytics-owner").toRealPath();if(!marker.startsWith(root)||!new String(read(marker,64),java.nio.charset.StandardCharsets.UTF_8).strip().equals(text("runId")))throw new IllegalArgumentException();
        var start=Instant.parse(text("createdAt"));var end=Instant.parse(text("expiresAt"));var now=clock.instant();
        if(start.isAfter(now)||!end.isAfter(start)||end.isAfter(start.plus(Duration.ofHours(2)))||!now.isBefore(end))throw new IllegalArgumentException();
    }catch(Exception e){throw new SecurityException("Generated Analytics fixture expired or changed");}}
    public void permit(UUID estate,UUID block){verify();if(!scopes.contains(new Scope(estate,block)))throw new SecurityException("Generated source scope denied");}
    public Set<Scope> scopes(){return Set.copyOf(scopes);}
    public void verifyEnvironmentalBindings(String rabbit){
        verify();
        if(!rabbit.equals(text("environmentRabbitUser"))||rabbit.equals(text("rabbitUser"))||
            !"agrosphere.analytics.environment.v1".equals(text("environmentQueue"))||
            !"78165fefbd980776ac44cb567e8fb213ac4fdd5ac979a4442e7e2d6d545647a6".equals(text("environmentSchemaSha256")))
            throw new SecurityException("Environmental queue/principal/schema binding changed");
        new com.agrosphere.analytics.domain.EnvironmentalProjection.Query(scopes.iterator().next().estateId(),scopes.iterator().next().blockId(),
            com.agrosphere.analytics.adapter.in.web.EnvironmentalController.utc(text("environmentWindowFrom")),
            com.agrosphere.analytics.adapter.in.web.EnvironmentalController.utc(text("environmentWindowTo")));
    }
    public String text(String key){return config.path(key).asText();}
    public void verifyBindings(String url,String runtime,String migration,String rabbit){try{
        verify();if(!sha(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(text("dbUrlSha256"))||!runtime.equals(text("runtimeUser"))||!migration.equals(text("migrationUser"))||runtime.equals(migration)||!rabbit.equals(text("rabbitUser")))throw new IllegalArgumentException();
    }catch(Exception e){throw new SecurityException("Generated Analytics binding changed");}}
    public void verifyDatabase(DataSource source){try(var c=source.getConnection();var statement=c.prepareStatement("SELECT current_user,current_database(),shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()")){verify();try(var r=statement.executeQuery()){
        if(!r.next()||!r.getString(1).equals(text("runtimeUser"))||!r.getString(2).equals("agrosphere_analytics")||!("ANL01:"+text("runId")).equals(r.getString(3)))throw new IllegalArgumentException();
    }}catch(Exception e){throw new SecurityException("Database is not the marked owned Analytics fixture");}}
}
