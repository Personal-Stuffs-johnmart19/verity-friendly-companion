package dev.johnmart19.veritycompanion;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Pages only the bounded public response already admitted by WebTools. */
final class WebPageText {
 static String page(String html,int offset,int limit){
  if(offset<0||limit<1||limit>1200)throw new IllegalArgumentException("offset >= 0; limit 1..1200 characters");
  String text=html.replaceAll("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"," ").replaceAll("(?s)<[^>]+>"," ").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replaceAll("\\s+"," ").trim();
  if(offset>text.length())throw new IllegalArgumentException("offset beyond available text");
  if(offset>0&&offset<text.length()&&Character.isLowSurrogate(text.charAt(offset))&&Character.isHighSurrogate(text.charAt(offset-1)))throw new IllegalArgumentException("offset splits Unicode code point; use returned next_offset");
  int end=Math.min(text.length(),offset+limit);
  if(end<text.length()&&end>offset&&Character.isHighSurrogate(text.charAt(end-1))&&Character.isLowSurrogate(text.charAt(end)))end--;
  if(end==offset&&end<text.length())end=Math.min(text.length(),end+2);
  JsonObject page=new JsonObject();page.addProperty("offset",offset);page.addProperty("total_characters",text.length());page.addProperty("text",text.substring(offset,end));
  if(end<text.length())page.addProperty("next_offset",end);
  page.addProperty("download_limit_bytes",1_000_000);page.addProperty("coverage","bounded_public_response; refetch may change content; compare content_sha256");
  try{page.addProperty("content_sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  return page.toString();
 }
}
