package dev.nova.deck;
import java.util.Map;
/** Only key events from an explicitly identified controller enter this parser. */
public final class ControllerKeyParser {
    public static final class Key {public final int code;public final boolean down,repeat;Key(int code,int value){this.code=code;down=value!=0;repeat=value==2;}}
    public static Key parse(String line,Map<Integer,Integer> layout){
        if(line==null)return null;String[] parts=line.trim().split("\\s+");if(parts.length<3)return null;
        try{int type=Integer.parseInt(parts[parts.length-3],16),scan=Integer.parseInt(parts[parts.length-2],16),value=Integer.parseInt(parts[parts.length-1],16);Integer key=layout.get(scan);
            return type==1&&key!=null&&value>=0&&value<=2?new Key(key,value):null;
        }catch(NumberFormatException ignored){return null;}
    }
}
