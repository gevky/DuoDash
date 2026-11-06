package dev.nova.deck;

import java.util.*;

/** Shared action sizes; packing uses half-slots without shrinking row touch targets. */
final class ControlSizePolicy {
    static final String SMALL="small",NORMAL="normal",WIDE="wide";
    static final int ROW_UNITS=24,MIN_SIZE=1,MAX_SIZE=6;
    private ControlSizePolicy() { }

    static String mode(String id,String small,String wide){
        return ids(wide).contains(id)?WIDE:ids(small).contains(id)?SMALL:NORMAL;
    }
    static boolean growFromTap(float x,float width){return width>0&&x>=width/2f;}
    static boolean tapGrows(String id,float x,float width,String small,String wide,String explicit,int columns){
        Integer selected=sizeMap(explicit).get(id);
        if(selected!=null)return selected==MIN_SIZE||growFromTap(x,width);
        int count=ControlLogic.actionColumns(columns);
        int legacyUnits=ids(wide).contains(id)?ROW_UNITS*2/count:ids(small).contains(id)?ROW_UNITS/(count*2):ROW_UNITS/count;
        return legacyUnits<=MIN_SIZE*4||growFromTap(x,width);
    }
    static Sizes resize(String id,String small,String wide,String explicit,int columns,boolean grow){
        LinkedHashSet<String> smallIds=ids(small),wideIds=ids(wide);LinkedHashMap<String,Integer> sizes=sizeMap(explicit);
        if(!ControlLogic.action(id))return new Sizes(small,wide,explicit,false);
        Integer selected=sizes.get(id);int next=0;
        if(selected!=null){
            int candidate=grow?Math.min(MAX_SIZE,selected+1):Math.max(MIN_SIZE,selected-1);
            if(candidate==selected)return new Sizes(small,wide,explicit,false);
            next=candidate;
        }
        else{
            int legacyUnits=wideIds.contains(id)?ROW_UNITS*2/ControlLogic.actionColumns(columns):smallIds.contains(id)?ROW_UNITS/(ControlLogic.actionColumns(columns)*2):ROW_UNITS/ControlLogic.actionColumns(columns);
            if(grow){for(int candidate=MIN_SIZE;candidate<=MAX_SIZE;candidate++)if(candidate*4>legacyUnits){next=candidate;break;}}
            else{for(int candidate=MAX_SIZE;candidate>=MIN_SIZE;candidate--)if(candidate*4<legacyUnits){next=candidate;break;}}
        }
        if(next==0)return new Sizes(small,wide,explicit,false);
        smallIds.remove(id);wideIds.remove(id);sizes.put(id,next);
        return new Sizes(smallIds,wideIds,sizes,true);
    }
    static int explicitSize(String id,String explicit){Integer value=sizeMap(explicit).get(id);return value==null?0:value;}
    static String normalizeSizes(String explicit){return encode(sizeMap(explicit));}
    static String sizeLabel(String id,String small,String wide,String explicit){int selected=explicitSize(id,explicit);return selected>0?Integer.toString(selected):mode(id,small,wide);}
    static String removeSize(String id,String explicit){LinkedHashMap<String,Integer> sizes=sizeMap(explicit);sizes.remove(id);return encode(sizes);}
    static int unitEdge(int width,int gap,int unit){return Math.round(unit*(width+gap)/(float)ROW_UNITS);}
    static int spanWidth(int width,int gap,int column,int span){return unitEdge(width,gap,column+span)-unitEdge(width,gap,column)-gap;}
    static String retainSizes(String explicit,List<String> activeAppIds){
        LinkedHashMap<String,Integer> kept=sizeMap(explicit);
        kept.entrySet().removeIf(entry->ControlCatalog.isApp(entry.getKey())&&(activeAppIds==null||!activeAppIds.contains(entry.getKey())));
        return encode(kept);
    }
    static boolean iconOnly(String id,String small,String explicit){return explicitSize(id,explicit)==MIN_SIZE||explicitSize(id,explicit)==0&&ids(small).contains(id);}
    static String retainSmall(String small,List<String> activeAppIds){
        LinkedHashSet<String> kept=ids(small);
        kept.removeIf(id->!ControlLogic.action(id)||ControlCatalog.isApp(id)&&(activeAppIds==null||!activeAppIds.contains(id)));
        return String.join(",",kept);
    }
    static List<ControlLogic.GridPlacement> pack(List<String> controls,String small,String wide,String explicit,int columns,int minimumTapUnits){
        int columnCount=ControlLogic.actionColumns(columns),row=0,column=0;
        List<ControlLogic.GridPlacement> result=new ArrayList<>();if(controls==null)return result;
        Set<String> smallIds=ids(small),wideIds=ids(wide);Map<String,Integer> sizes=sizeMap(explicit);
        for(String id:controls){
            Integer size=sizes.get(id);
            int normalSpan=ROW_UNITS/columnCount;
            int span=size!=null?size*4:wideIds.contains(id)?ROW_UNITS*2/columnCount:smallIds.contains(id)?ROW_UNITS/(columnCount*2):normalSpan;
            if(size==null&&smallIds.contains(id)&&span<Math.max(1,minimumTapUnits))span=Math.max(normalSpan,minimumTapUnits);
            if(size!=null&&span<Math.max(1,minimumTapUnits))span=Math.max(normalSpan,minimumTapUnits);
            span=Math.min(ROW_UNITS,span);
            if(column+span>ROW_UNITS){row++;column=0;}
            result.add(new ControlLogic.GridPlacement(id,row,column,span));column+=span;
            if(column==ROW_UNITS){row++;column=0;}
        }
        return result;
    }
    private static LinkedHashMap<String,Integer> sizeMap(String value){
        LinkedHashMap<String,Integer> result=new LinkedHashMap<>();
        if(value==null||value.isEmpty())return result;
        for(String item:value.split(",")){
            int separator=item.lastIndexOf(':');if(separator<=0||separator==item.length()-1)continue;
            String id=item.substring(0,separator);if(!ControlLogic.action(id))continue;
            try{int size=Integer.parseInt(item.substring(separator+1));if(size>=MIN_SIZE&&size<=MAX_SIZE)result.put(id,size);}catch(NumberFormatException ignored){}
        }
        return result;
    }
    private static String encode(Map<String,Integer> values){
        List<String> entries=new ArrayList<>();for(Map.Entry<String,Integer> entry:values.entrySet())entries.add(entry.getKey()+":"+entry.getValue());return String.join(",",entries);
    }
    private static LinkedHashSet<String> ids(String value){
        LinkedHashSet<String> result=new LinkedHashSet<>();
        for(String id:ControlLogic.cleanHidden(value).split(","))if(!id.isEmpty()&&ControlLogic.action(id))result.add(id);
        return result;
    }
    static final class Sizes {
        final String small,wide,explicit;
        final boolean changed;
        Sizes(Set<String> small,Set<String> wide,Map<String,Integer> explicit,boolean changed){this(String.join(",",small),String.join(",",wide),encode(explicit),changed);}
        Sizes(String small,String wide,String explicit,boolean changed){this.small=small;this.wide=wide;this.explicit=explicit;this.changed=changed;}
    }
}
