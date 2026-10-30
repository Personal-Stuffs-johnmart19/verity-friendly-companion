package dev.johnmart19.veritycompanion;
import java.net.*;import java.net.http.*;import java.time.Duration;import java.nio.charset.StandardCharsets;import javax.xml.parsers.*;import org.w3c.dom.*;import java.io.*;
final class WebTools {
 static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 static String fetch(String url)throws Exception{
  if(ServerBridge.nativeRuntime()&&!NativeRuntime.get().externalAllowed())throw new IOException("External web requests disabled by server configuration");
  URI uri=URI.create(url);for(int redirect=0;redirect<4;redirect++){
   if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))throw new IllegalArgumentException("Only public HTTPS URLs on port 443 are allowed");
   for(InetAddress ip:InetAddress.getAllByName(uri.getHost())){byte[] b=ip.getAddress();if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress()||(b.length==16&&((b[0]&254)==252))||(b.length==4&&((b[0]&255)==100&&(b[1]&255)>=64&&(b[1]&255)<=127)))throw new IllegalArgumentException("Private/local network URLs are not web sources");}
   var response=HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("User-Agent","VerityCompanion/1.0").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
   try(InputStream in=new DeadlineInputStream(response.body(),20000)){if(response.statusCode()>=300&&response.statusCode()<400){uri=uri.resolve(response.headers().firstValue("location").orElseThrow());continue;}if(response.statusCode()!=200)throw new IOException("Website HTTP "+response.statusCode());return new String(in.readNBytes(1_000_000),StandardCharsets.UTF_8);}
  }throw new IOException("Too many redirects");
 }
 static String search(String query)throws Exception{
  if(query.isBlank())throw new IllegalArgumentException("Empty search query");String xml=fetch("https://www.bing.com/search?format=rss&q="+URLEncoder.encode(query,StandardCharsets.UTF_8));
  DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setXIncludeAware(false);f.setExpandEntityReferences(false);
  NodeList items=f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getElementsByTagName("item");StringBuilder result=new StringBuilder();for(int i=0;i<Math.min(8,items.getLength());i++){Element e=(Element)items.item(i);for(String tag:new String[]{"title","link","description"}){Node n=e.getElementsByTagName(tag).item(0);if(n!=null)result.append(n.getTextContent()).append('\n');}result.append('\n');}return result.length()==0?"No web results; try a narrower query.":result.toString();
 }
 static String read(String url,int offset,int limit)throws Exception{return WebPageText.page(fetch(url),offset,limit);}
}
