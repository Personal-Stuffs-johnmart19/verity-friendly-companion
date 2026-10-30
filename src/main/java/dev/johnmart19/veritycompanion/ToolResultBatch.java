package dev.johnmart19.veritycompanion;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded transport receipts. A result received is not a claim that its underlying action succeeded. */
public final class ToolResultBatch {
 public static final int MAX_ACTIONS=16,MAX_BYTES=10_000,MAX_RESULT_BYTES=6_200;
 private record Prefix(String text,int bytes){}
 private record Receipt(String tool,boolean toolTruncated,boolean mutation,Prefix result,int originalChars){}
 private final List<Receipt> receipts=new ArrayList<>();
 public void add(String tool,boolean mutation,String result){
  if(receipts.size()>=MAX_ACTIONS)throw new IllegalStateException("At most 16 tool action receipts per batch");
  Objects.requireNonNull(tool,"tool");String value=result==null?"null":result;Prefix name=prefix(tool,128);
  receipts.add(new Receipt(name.text(),name.text().length()<tool.length(),mutation,prefix(value,MAX_RESULT_BYTES),value.length()));
 }
 public String render(){
  Prefix[] selected=receipts.stream().map(Receipt::result).toArray(Prefix[]::new);String complete=encode(selected);
  if(bytes(complete)<=MAX_BYTES)return complete;
  Prefix[] empty=new Prefix[receipts.size()];Arrays.fill(empty,new Prefix("",0));int available=MAX_BYTES-bytes(encode(empty));
  // Preserve small complete results first, then divide remaining capacity across the larger pages.
  List<Integer> order=new ArrayList<>();for(int i=0;i<receipts.size();i++)order.add(i);order.sort(Comparator.comparingInt(i->receipts.get(i).result.bytes));
  for(int index=0;index<order.size();index++){int position=order.get(index),share=Math.max(0,available/(order.size()-index));Prefix result=receipts.get(position).result;selected[position]=prefix(result.text,Math.min(result.bytes,share));available-=selected[position].bytes;}
  String rendered=encode(selected);if(bytes(rendered)>MAX_BYTES)throw new IllegalStateException("Tool result metadata exceeded transport budget");return rendered;
 }
 private String encode(Prefix[] selected){StringBuilder out=new StringBuilder();boolean shortened=false;for(int i=0;i<receipts.size();i++)shortened|=truncated(receipts.get(i),selected[i]);
  out.append("{\"transport_truncated\":").append(shortened).append(",\"results\":[");
  for(int i=0;i<receipts.size();i++){Receipt receipt=receipts.get(i);Prefix result=selected[i];boolean cut=truncated(receipt,result);if(i>0)out.append(',');out.append("{\"action\":").append(i).append(",\"tool\":");quote(out,receipt.tool);out.append(",\"status\":\"result_received\",\"mutation\":").append(receipt.mutation);
   if(receipt.toolTruncated)out.append(",\"tool_name_truncated\":true");
   if(receipt.mutation)out.append(",\"already_executed\":true,\"do_not_replay\":true");
   out.append(",\"result\":");quote(out,result.text);out.append(",\"transport_truncated\":").append(cut);
   if(cut){out.append(",\"more_data_required\":true");if(receipt.mutation)out.append(",\"recovery\":\"inspect_state_before_any_new_mutation\"");else out.append(",\"retry_read_only_individually\":true,\"retry_same_offset_or_cursor\":true");}
   out.append('}');
  }
  out.append(']');if(shortened){if(receipts.stream().anyMatch(receipt->!receipt.mutation))out.append(",\"read_only_recovery\":\"Repeat a truncated read alone at the SAME offset/cursor; narrow the query or use a smaller supported page limit. Do not skip rows or invent a next_offset. If unpaged, narrow the query.\"");if(receipts.stream().anyMatch(Receipt::mutation))out.append(",\"mutation_recovery\":\"A mutation was already attempted; never replay it to recover output. Inspect current state with a read-only tool. Receipt status does not assert success.\"");}return out.append('}').toString();
 }
 private static boolean truncated(Receipt receipt,Prefix selected){return receipt.toolTruncated||selected.text.length()<receipt.originalChars;}
 private static int bytes(String value){return value.getBytes(StandardCharsets.UTF_8).length;}
 /** Counts the JSON string representation, without allocating/encoding the unbounded original. */
 private static Prefix prefix(String value,int budget){int end=0,bytes=0;while(end<value.length()){char current=value.charAt(end);boolean pair=Character.isHighSurrogate(current)&&end+1<value.length()&&Character.isLowSurrogate(value.charAt(end+1));int cost=pair?4:current=='"'||current=='\\'||current=='\b'||current=='\t'||current=='\n'||current=='\f'||current=='\r'?2:current<32||current=='\u2028'||current=='\u2029'||Character.isSurrogate(current)?6:current<128?1:current<2048?2:3;if(bytes+cost>budget)break;bytes+=cost;end+=pair?2:1;}return new Prefix(value.substring(0,end),bytes);}
 private static void quote(StringBuilder out,String value){out.append('"');for(int i=0;i<value.length();i++){char current=value.charAt(i);switch(current){case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\b'->out.append("\\b");case '\t'->out.append("\\t");case '\n'->out.append("\\n");case '\f'->out.append("\\f");case '\r'->out.append("\\r");default->{if(Character.isHighSurrogate(current)&&i+1<value.length()&&Character.isLowSurrogate(value.charAt(i+1))){out.append(current).append(value.charAt(++i));}else if(current<32||current=='\u2028'||current=='\u2029'||Character.isSurrogate(current)){out.append("\\u");for(int shift=12;shift>=0;shift-=4)out.append("0123456789abcdef".charAt((current>>shift)&15));}else out.append(current);}}}out.append('"');}
}
