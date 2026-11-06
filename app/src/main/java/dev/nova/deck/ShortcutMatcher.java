package dev.nova.deck;
import java.util.*;
/** Only the completing key is consumed, keeping prefix presses balanced for foreground apps. */
public final class ShortcutMatcher {
    public static final class Result {public final boolean consume,toggle;Result(boolean c,boolean t){consume=c;toggle=t;}}
    private final List<Set<Integer>> bindings=new ArrayList<>();
    private final Set<Integer> pressed=new HashSet<>(),consumed=new HashSet<>();
    public ShortcutMatcher(String shortcuts){for(String chord:normalize(shortcuts).split(";"))if(!chord.isEmpty())bindings.add(keys(chord));}
    public Result event(int key,boolean down,boolean repeat){
        if(!down){pressed.remove(key);return new Result(consumed.remove(key),false);}
        if(repeat||!pressed.add(key))return new Result(consumed.contains(key),false);
        for(Set<Integer> chord:bindings)if(chord.contains(key)&&pressed.containsAll(chord)){consumed.add(key);return new Result(true,true);}
        return new Result(false,false);
    }
    private static Set<Integer> keys(String chord){
        Set<Integer> result=new TreeSet<>();try{for(String key:chord.split(",")){int n=Integer.parseInt(key.trim());if(n<=0||n>400||n==3||n==26)return Collections.emptySet();result.add(n);}}catch(NumberFormatException error){return Collections.emptySet();}
        return result.size()>3?Collections.emptySet():result;
    }
    private static String encode(Set<Integer> keys){List<String> text=new ArrayList<>();for(int key:keys)text.add(Integer.toString(key));return String.join(",",text);}
    public static String normalize(String value){
        LinkedHashSet<String> result=new LinkedHashSet<>();
        if(value!=null)for(String chord:value.split(";")){Set<Integer> keys=keys(chord);if(!keys.isEmpty()&&result.size()<8)result.add(encode(keys));}
        return String.join(";",result);
    }
    /** True only when at least one non-reserved, parseable toggle binding survives normalization. */
    public static boolean hasConfiguredToggle(String value){return !normalize(value).isEmpty();}
    public static boolean canAdd(String value,int[] values){
        Set<Integer> proposed=new TreeSet<>();for(int n:values)proposed.add(n);
        if(proposed.isEmpty()||!keys(encode(proposed)).equals(proposed))return false;
        String existing=normalize(value);if(existing.isEmpty())return true;
        if(existing.split(";").length>=8)return false;
        for(String chord:existing.split(";")){Set<Integer> old=keys(chord);if(old.containsAll(proposed)||proposed.containsAll(old))return false;}
        return true;
    }
}
