package dev.nova.deck;
import java.util.*;

public final class ControlLogic {
    public static final String DEFAULT_ORDER=ControlCatalog.defaultOrder();
    /** Connection-only visibility; never changes the user's saved layout. */
    public static boolean visibleOnDisplay(String id,boolean externalConnected){
        return externalConnected||!("both".equals(id)||"upper".equals(id)||"audio_upper".equals(id)||"audio_main".equals(id));
    }
    public static String outputKey(String identity){return identity==null||identity.isEmpty()?"handheld":"output:"+identity;}
    public static String normalizeName(String name,String fallback){
        String value=name==null?"":name.replaceAll("[\\p{Z}\\s]+"," ").replaceAll("\\p{C}","").trim();
        if(value.isEmpty())value=fallback==null||fallback.trim().isEmpty()?"External display":fallback.trim();
        return value.codePointCount(0,value.length())>40?value.substring(0,value.offsetByCodePoints(0,40)):value;
    }
    private static LinkedHashSet<String> controls(String input){
        LinkedHashSet<String> result=new LinkedHashSet<>();
        if(input!=null)for(String id:input.split(","))if(ControlCatalog.known(id.trim()))result.add(id.trim());
        return result;
    }
    static String hideMissingOptionalControls(String storedOrder,String hidden,String... optionalIds){
        Set<String> existing=controls(storedOrder);LinkedHashSet<String> removed=controls(hidden);
        for(String id:optionalIds)if(ControlCatalog.known(id)&&!existing.contains(id))removed.add(id);
        return String.join(",",removed);
    }
    public static String cleanOrder(String order){LinkedHashSet<String> ids=controls(order);ids.addAll(Arrays.asList(DEFAULT_ORDER.split(",")));return String.join(",",ids);}
    public static String[] mergeLegacyFps(String order,String hidden){
        String source=order==null||order.isEmpty()?DEFAULT_ORDER:order;LinkedHashSet<String> ids=new LinkedHashSet<>();boolean hadLegacy=false;
        for(String raw:source.split(",")){String id=raw.trim();if(id.equals("main_fps")||id.equals("upper_fps")){hadLegacy=true;ids.add("fps");}else if(ControlCatalog.known(id))ids.add(id);}
        LinkedHashSet<String> removed=controls(hidden);boolean oldMain=hidden!=null&&Arrays.asList(hidden.split(",")).contains("main_fps"),oldUpper=hidden!=null&&Arrays.asList(hidden.split(",")).contains("upper_fps");
        removed.remove("main_fps");removed.remove("upper_fps");
        if(hadLegacy){if(oldMain&&oldUpper)removed.add("fps");else removed.remove("fps");}
        return new String[]{String.join(",",ids),String.join(",",removed)};
    }
    public static String legacyFpsSelection(String order,String hidden){
        if(order==null||(!order.contains("main_fps")&&!order.contains("upper_fps")))return null;
        Set<String> removed=new HashSet<>();if(hidden!=null)removed.addAll(Arrays.asList(hidden.split(",")));
        boolean main=order.contains("main_fps")&&!removed.contains("main_fps"),upper=order.contains("upper_fps")&&!removed.contains("upper_fps");
        if(main&&!upper)return "main";if(upper&&!main)return "upper";if(!main)return null;
        return order.indexOf("main_fps")<=order.indexOf("upper_fps")?"main":"upper";
    }
    public static String cleanHidden(String hidden){return String.join(",",controls(hidden));}
    /** Canonical cleanup for a removed app shortcut and hidden legacy app records. */
    public static final class AppControlState {
        public final String order,hidden,wide;
        public final List<String> pinnedAppIds;
        AppControlState(String order,String hidden,String wide,List<String> pinnedAppIds){
            this.order=order;this.hidden=hidden;this.wide=wide;this.pinnedAppIds=Collections.unmodifiableList(pinnedAppIds);
        }
    }
    public static AppControlState cleanAppControls(String order,String hidden,String wide,List<String> pinnedAppIds,String deleteAppId){
        Set<String> removed=new LinkedHashSet<>(),candidates=new LinkedHashSet<>();
        if(deleteAppId!=null&&ControlCatalog.isApp(deleteAppId))removed.add(deleteAppId);
        List<String> apps=new ArrayList<>();Set<String> seenApps=new LinkedHashSet<>();if(pinnedAppIds!=null)for(String id:pinnedAppIds)if(ControlCatalog.isApp(id)&&seenApps.add(id)){apps.add(id);candidates.add(id);}
        Set<String> hiddenIds=new LinkedHashSet<>(Arrays.asList(cleanHidden(hidden).split(",")));
        List<String> orderIds=new ArrayList<>(Arrays.asList(cleanOrder(order).split(",")));
        for(String id:orderIds)if(ControlCatalog.isApp(id))candidates.add(id);
        for(String id:hiddenIds)if(ControlCatalog.isApp(id))candidates.add(id);
        for(String id:cleanHidden(wide).split(","))if(ControlCatalog.isApp(id))candidates.add(id);
        Set<String> activeApps=new LinkedHashSet<>(apps);activeApps.removeAll(hiddenIds);activeApps.removeAll(removed);
        for(String id:candidates)if(!activeApps.contains(id))removed.add(id);
        apps.removeIf(removed::contains);
        orderIds.removeIf(removed::contains);
        hiddenIds.removeAll(removed);
        Set<String> wideIds=new LinkedHashSet<>(Arrays.asList(cleanHidden(wide).split(",")));
        wideIds.removeAll(removed);
        return new AppControlState(String.join(",",orderIds),String.join(",",hiddenIds),String.join(",",wideIds),apps);
    }
    public static int actionColumns(int columns){return Math.max(2,Math.min(4,columns));}
    public static final class GridPlacement {
        public final String id; public final int row,column,span;
        GridPlacement(String id,int row,int column,int span){this.id=id;this.row=row;this.column=column;this.span=span;}
    }
    public static List<GridPlacement> packActionGrid(List<String> ids,String wide,int columns){
        columns=actionColumns(columns);Set<String> wideIds=controls(wide);List<GridPlacement> result=new ArrayList<>();int row=0,column=0;
        if(ids==null)return result;
        for(String id:ids){int span=wideIds.contains(id)?2:1;if(column+span>columns){row++;column=0;}result.add(new GridPlacement(id,row,column,span));column+=span;if(column==columns){row++;column=0;}}
        return result;
    }
    public static boolean action(String id){ControlCatalog.Entry entry=ControlCatalog.find(id);return ControlCatalog.isApp(id)||entry!=null&&entry.kind.equals("action");}
    public static int actionCount(String order,String hidden){Set<String> removed=controls(hidden);int count=0;for(String id:controls(cleanOrder(order)))if(action(id)&&!removed.contains(id))count++;return count;}
    public static boolean showAddControl(boolean editing,boolean empty){return empty||editing;}
    public static boolean fpsMarkerFilled(boolean externalSelected,boolean externalRow){return externalSelected==externalRow;}
    public static float removeBadgeRadius(float width,float height,float target){if(width<=0||height<=0||target<=0)return 0;return Math.min(target,Math.min(width,height)/2f);}
    public static float removeBadgeCenterX(float width,float height,float target){float radius=removeBadgeRadius(width,height,target);if(radius<=0)return 0;return Math.max(radius,Math.min(width-radius,width-target));}
    public static float removeBadgeCenterY(float width,float height,float target){float radius=removeBadgeRadius(width,height,target);if(radius<=0)return 0;return Math.max(radius,Math.min(height-radius,target));}
    public static boolean inRemoveBadge(float x,float y,float width,float height,float target){
        float radius=removeBadgeRadius(width,height,target);if(radius<=0||x<0||y<0||x>width||y>height)return false;
        float dx=x-removeBadgeCenterX(width,height,target),dy=y-removeBadgeCenterY(width,height,target);
        return dx*dx+dy*dy<=radius*radius;
    }
    public static boolean shouldRemoveBadge(boolean capturedOnDown,boolean releasedInside){return capturedOnDown&&releasedInside;}
    /** Newly enabled controls append within their visible group, regardless of old catalog position. */
    public static String appendControlToGroupEnd(String order,String hidden,String id){
        String cleaned=cleanOrder(order);if(!ControlCatalog.known(id))return cleaned;
        String group=ControlMovePolicy.section(id);if(group.isEmpty())return cleaned;
        List<String> ids=new ArrayList<>(Arrays.asList(cleaned.split(",")));ids.remove(id);
        Set<String> removed=controls(hidden);int last=-1;
        for(int i=0;i<ids.size();i++)if(!removed.contains(ids.get(i))&&group.equals(ControlMovePolicy.section(ids.get(i))))last=i;
        ids.add(last<0?ids.size():last+1,id);return String.join(",",ids);
    }
    public static String moveOrder(String order,String from,String to){
        List<String> ids=new ArrayList<>(Arrays.asList(cleanOrder(order).split(",")));int source=ids.indexOf(from),target=ids.indexOf(to);
        if(source<0||target<0||source==target)return String.join(",",ids);String moving=ids.remove(source);ids.add(target,moving);return String.join(",",ids);
    }
    /** Returns the complete next order/hidden pair, or null when the replacement is invalid. */
    public static String[] replaceLayout(String order,String hidden,String oldId,String newId){
        if(oldId==null||newId==null||oldId.equals(newId)||!ControlCatalog.known(oldId)||!ControlCatalog.known(newId)||action(oldId)!=action(newId))return null;
        List<String> ids=new ArrayList<>(Arrays.asList(cleanOrder(order).split(",")));
        LinkedHashSet<String> removed=new LinkedHashSet<>(controls(hidden));
        int old=ids.indexOf(oldId),next=ids.indexOf(newId);
        if(old<0||removed.contains(oldId))return null;
        if(next>=0&&!removed.contains(newId))return null;
        if(next<0&&!ControlCatalog.isApp(newId))return null;
        if(next>=0){ids.remove(next);if(next<old)old--;}
        ids.set(old,newId);removed.add(oldId);removed.remove(newId);
        return new String[]{String.join(",",ids),String.join(",",removed)};
    }
    public static String[] upgradeLayout(String order,String hidden){
        String legacy="volume,main,upper,both,swap,up,down,restore,refresh,temp,memory,power";
        String previous="both,upper,main,audio_upper,audio_main,volume,swap,up,down,wifi,bluetooth,rotation,dnd,keyboard,screenshot,apps,shortcuts,display_settings,audio_settings,fan,performance,temp,fan_speed,cpu,gpu,memory,power,refresh";
        if(order==null||order.isEmpty()||order.equals(legacy)&&(hidden==null||hidden.isEmpty())||order.equals(previous)&&"audio_settings,fan,performance,fan_speed,refresh".equals(hidden))return new String[]{ControlCatalog.defaultOrder(),ControlCatalog.defaultHidden()};
        String[] merged=mergeLegacyFps(order,hidden);order=merged[0];hidden=merged[1];
        Set<String> original=new HashSet<>(Arrays.asList(order.split(",")));LinkedHashSet<String> removed=controls(hidden);
        for(ControlCatalog.Entry entry:ControlCatalog.entries())if(!original.contains(entry.id))removed.add(entry.id);
        return new String[]{cleanOrder(order),String.join(",",removed)};
    }
    public static final class Task {
        public final int id, display;
        public final String pkg;
        public final boolean visible, standard;
        public Task(int id, int display, String pkg, boolean visible, boolean standard) {
            this.id=id; this.display=display; this.pkg=pkg; this.visible=visible; this.standard=standard;
        }
    }
    public static final class Move {
        public final int task, from, to;
        public Move(int task, int from, int to) { this.task=task; this.from=from; this.to=to; }
    }
    public static boolean externalUserTask(Task task){
        if(task==null||!task.standard||task.pkg==null)return false;
        String pkg=task.pkg;
        return !pkg.equals("dev.nova.deck")&&!pkg.equals("com.android.systemui")
            &&!pkg.equals("com.android.launcher3")&&!pkg.startsWith("com.retrohrai.launcher")
            &&!pkg.equals("com.android.permissioncontroller")&&!pkg.equals("com.google.android.permissioncontroller");
    }
    public static boolean hasExternalUserTask(List<Task> tasks,int display){
        if(tasks!=null)for(Task task:tasks)if(task.display==display&&externalUserTask(task))return true;
        return false;
    }
    public static int percent(int value, boolean main) { return Math.max(main ? 5 : 0, Math.min(100,value)); }
    public static float dimAlpha(int value) {
        return (float)(1 - Math.pow(percent(value,false)/100.0,1/2.2));
    }
    public static boolean shouldMirrorExternal(boolean compositionUsesMain,boolean visibleMirrorTask,boolean independentWindow,boolean foregroundAppTask) {
        if(foregroundAppTask)return false;
        return compositionUsesMain||visibleMirrorTask&&!independentWindow;
    }
    public static Task foreground(List<Task> tasks, int display) {
        for(Task task:tasks) if(task.display==display&&task.visible&&task.standard&&task.pkg!=null
                &&!task.pkg.equals("dev.nova.deck")&&!task.pkg.equals("com.android.systemui")
                &&!task.pkg.equals("com.android.permissioncontroller")&&!task.pkg.equals("com.google.android.permissioncontroller")) return task;
        return null;
    }
    public static List<Move> swapPlan(List<Task> tasks, int external) {
        List<Move> moves=new ArrayList<>();
        if(external<=0) return moves;
        Task main=foreground(tasks,0), upper=foreground(tasks,external);
        if(main!=null) moves.add(new Move(main.id,0,external));
        if(upper!=null) moves.add(new Move(upper.id,external,0));
        return moves;
    }
}
