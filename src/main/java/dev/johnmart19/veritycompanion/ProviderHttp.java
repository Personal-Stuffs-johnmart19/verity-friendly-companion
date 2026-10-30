package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Bounded provider transport. Redirects never receive server credentials. */
final class ProviderHttp {
 static {NativeBootstrap.preparePlatform();}
 record Result(int status, JsonObject body, String retryAfter) {}
 interface Transport { Result send(URI uri, JsonObject body, String header, String token, long timeoutMillis) throws Exception; }
 static final Transport NETWORK=ProviderHttp::send;
 private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 static Result send(URI uri,JsonObject body,String header,String token,long timeoutMillis)throws Exception {
  if(timeoutMillis<1)throw new IOException("Provider deadline exceeded");
  long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
  var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMillis)).header("Content-Type","application/json");
  if(token!=null&&!token.isBlank())builder.header(header,token);
  if(body==null)builder.GET();else builder.POST(HttpRequest.BodyPublishers.ofString(body.toString(),StandardCharsets.UTF_8));
  var response=HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
  long remaining=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
  if(remaining<1){response.body().close();throw new java.net.SocketTimeoutException("Provider deadline exceeded");}
  try(var input=new DeadlineInputStream(response.body(),remaining)){
   int limit=response.statusCode()==200?9_000_000:65_536;
   byte[] raw=input.readNBytes(limit+1);
   if(raw.length>limit)throw new IOException("Provider response exceeds size limit");
   JsonObject parsed=new JsonObject();
   try{parsed=JsonParser.parseString(new String(raw,StandardCharsets.UTF_8)).getAsJsonObject();}
   catch(RuntimeException invalid){if(response.statusCode()==200)throw new IOException("Invalid provider JSON");}
   return new Result(response.statusCode(),parsed,response.headers().firstValue("Retry-After").orElse(""));
  }
 }
 static URI loopback(String endpoint){
  URI uri=URI.create(endpoint);
  if(!"http".equals(uri.getScheme())||!java.util.Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||uri.getPath().equals("/")))throw new IllegalArgumentException("LM Studio must use a loopback HTTP origin");
  return URI.create(endpoint.replaceAll("/+$",""));
 }
 static JsonObject object(String json){return JsonParser.parseString(json).getAsJsonObject();}
 static String text(JsonObject object,String name,String fallback){return object.has(name)&&!object.get(name).isJsonNull()?object.get(name).getAsString():fallback;}
 static int integer(JsonObject object,String name,int fallback){return object.has(name)&&!object.get(name).isJsonNull()?object.get(name).getAsInt():fallback;}
 static JsonArray array(JsonObject object,String name){return object.has(name)&&object.get(name).isJsonArray()?object.getAsJsonArray(name):new JsonArray();}
}
