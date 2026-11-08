package dev.nova.deck;

import java.util.*;

/** Only the completing key is consumed, keeping prefix presses balanced for foreground apps. */
public final class ShortcutMatcher {
    public static final class Result {
        public final boolean consume,toggle;
        public final String action;
        Result(boolean consume,String action){this.consume=consume;this.action=action;this.toggle=ShortcutBindings.TOGGLE.equals(action);}
    }
    private static final class Binding {
        final String action;
        final Set<Integer> keys;
        Binding(String action,Set<Integer> keys){this.action=action;this.keys=keys;}
    }
    private final List<Binding> bindings=new ArrayList<>();
    private final List<Binding> pending=new ArrayList<>();
    private final Set<Integer> pressed=new HashSet<>(),consumed=new HashSet<>();

    /** Legacy constructor: the sole action remains the dashboard toggle. */
    public ShortcutMatcher(String shortcuts){this(shortcuts,"");}
    public ShortcutMatcher(String toggles,String controls){
        for(String chord:normalize(toggles).split(";"))if(!chord.isEmpty())bindings.add(new Binding(ShortcutBindings.TOGGLE,keys(chord)));
        ShortcutBindings model=new ShortcutBindings(toggles,controls);
        for(Map.Entry<String,String> entry:model.controls().entrySet())
            for(String chord:entry.getValue().split(";"))if(!chord.isEmpty())bindings.add(new Binding(entry.getKey(),keys(chord)));
    }

    public Result event(int key,boolean down,boolean repeat){
        if(!down){
            pressed.remove(key);
            boolean consume=consumed.remove(key);
            List<Binding> released=new ArrayList<>();
            for(Binding binding:pending)if(binding.keys.contains(key))released.add(binding);
            if(released.isEmpty())return new Result(consume,null);
            Binding selected=mostSpecific(released);
            removePendingSubsets(selected.keys);
            pending.remove(selected);
            return new Result(consume,selected.action);
        }
        if(repeat||!pressed.add(key))return new Result(consumed.contains(key),null);
        List<Binding> complete=new ArrayList<>();
        for(Binding binding:bindings)if(binding.keys.contains(key)&&pressed.containsAll(binding.keys))complete.add(binding);
        if(complete.isEmpty())return new Result(false,null);
        Binding selected=mostSpecific(complete);
        for(Binding binding:complete)removePendingSubsets(binding.keys);
        consumed.add(key);
        if(hasStrictSuperset(selected.keys)){
            if(!containsBinding(pending,selected))pending.add(selected);
            return new Result(true,null);
        }
        return new Result(true,selected.action);
    }

    /** Clears partial chords when shortcut monitoring is suspended during capture. */
    public void reset(){pressed.clear();consumed.clear();pending.clear();}

    private boolean hasStrictSuperset(Set<Integer> chord){
        for(Binding binding:bindings)if(binding.keys.size()>chord.size()&&binding.keys.containsAll(chord))return true;
        return false;
    }
    private void removePendingSubsets(Set<Integer> chord){pending.removeIf(binding->binding.keys.size()<chord.size()&&chord.containsAll(binding.keys));}
    private static boolean containsBinding(List<Binding> values,Binding target){
        for(Binding value:values)if(value.action.equals(target.action)&&value.keys.equals(target.keys))return true;
        return false;
    }
    private static Binding mostSpecific(List<Binding> candidates){
        Binding selected=null;
        for(Binding candidate:candidates){
            if(selected==null||candidate.keys.size()>selected.keys.size()){selected=candidate;continue;}
            if(candidate.keys.size()!=selected.keys.size())continue;
            if(candidate.keys.equals(selected.keys)){
                if(ShortcutBindings.TOGGLE.equals(candidate.action)&&!ShortcutBindings.TOGGLE.equals(selected.action))selected=candidate;
            }else if(ShortcutBindings.TOGGLE.equals(selected.action)&&!ShortcutBindings.TOGGLE.equals(candidate.action))selected=candidate;
        }
        return selected;
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
        for(String chord:existing.split(";")){Set<Integer> old=keys(chord);if(old.equals(proposed))return false;}
        return true;
    }
}
