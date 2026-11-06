package dev.nova.deck.bridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Safe logical-size choices derived from one display's native orientation. */
final class LogicalCanvas {
    final String id, label;
    final int width, height;
    LogicalCanvas(String id, String label, int width, int height) {
        this.id=id; this.label=label; this.width=width; this.height=height;
    }
    JSONObject json() throws Exception {
        return new JSONObject().put("id",id).put("label",label).put("width",width).put("height",height);
    }
    static Map<String,LogicalCanvas> choices(int width,int height) {
        LinkedHashMap<String,LogicalCanvas> result=new LinkedHashMap<>();
        add(result,"native","Native canvas",width,height);
        int reducedW=width*3/4, reducedH=height*3/4;
        reducedW-=reducedW%2; reducedH-=reducedH%2;
        add(result,"reduced","Lower resolution",reducedW,reducedH);
        int fourH=Math.min(height,(width*3/4)); fourH-=fourH%4;
        add(result,"ratio_4_3","4:3 canvas",fourH*4/3,fourH);
        int wideW=Math.min(width,(height*16/9)); wideW-=wideW%16;
        add(result,"ratio_16_9","16:9 canvas",wideW,wideW*9/16);
        return result;
    }
    static int[] toNaturalSize(int logicalWidth,int logicalHeight,int rotation){
        return rotation%2==0?new int[]{logicalWidth,logicalHeight}:new int[]{logicalHeight,logicalWidth};
    }
    private static void add(Map<String,LogicalCanvas> result,String id,String label,int width,int height) {
        if(width<320||height<320)return;
        for(LogicalCanvas old:result.values()) if(old.width==width&&old.height==height)return;
        result.put(id,new LogicalCanvas(id,label,width,height));
    }
    static JSONArray jsonChoices(Map<String,LogicalCanvas> choices) throws Exception {
        JSONArray out=new JSONArray();for(LogicalCanvas choice:choices.values())out.put(choice.json());return out;
    }
}
