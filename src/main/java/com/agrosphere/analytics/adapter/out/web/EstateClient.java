package com.agrosphere.analytics.adapter.out.web;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

public final class EstateClient {
 private final URI base;
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
 private final ObjectMapper json=new ObjectMapper();
 public EstateClient(String url){base=URI.create(url);if(base.getHost()==null||base.getUserInfo()!=null||!Set.of("http","https").contains(base.getScheme()))throw new IllegalArgumentException("Estate URL");}
 private JsonNode get(String path,String bearer){
  if(bearer==null||bearer.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Estate identity unavailable");
  try{
   var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+bearer).GET().build();
   var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
   try(var input=response.body()){
    if(response.statusCode()==404)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown reference");
    if(response.statusCode()==401||response.statusCode()==403)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Reference denied");
    if(response.statusCode()!=200)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Estate unavailable");
    byte[] bytes=input.readNBytes(65537);if(bytes.length>65536)throw new IllegalArgumentException();return json.readTree(bytes);
   }
  }catch(ResponseStatusException e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Estate unavailable");}
 }
 public boolean active(UUID estate,UUID block,String bearer){
  var e=get("/api/v1/estates/"+estate,bearer);var b=get("/api/v1/blocks/"+block,bearer);
  if(!estate.toString().equals(e.path("id").asText())||!block.toString().equals(b.path("id").asText())||!estate.toString().equals(b.path("estateId").asText()))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Invalid reference response");
  if(!"ACTIVE".equals(e.path("status").asText())||!"ACTIVE".equals(b.path("status").asText())||!"RUBBER".equals(b.path("cropType").asText()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Inactive rubber reference");
  return true;
 }
}
