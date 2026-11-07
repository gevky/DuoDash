package dev.nova.deck;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

public final class ProfileStore {
    private final SharedPreferences prefs;
    private final Context context;
    public ProfileStore(Context context) { this.context=context.getApplicationContext();prefs=context.getSharedPreferences("display_profiles",Context.MODE_PRIVATE); }
    public String name(String identity) { return prefs.getString("outputName:"+identity,null); }
    public void name(String identity,String name) { prefs.edit().putString("outputName:"+identity,ControlLogic.normalizeName(name,"External display")).apply(); }
    public String displayMode(String profile){
        String key=profile+":displayMode";
        if(prefs.contains(key))return DisplayModePolicy.normalizeMode(prefs.getString(key,DisplayModePolicy.DUAL));
        String migrated=DisplayModePolicy.migrateLegacyMode(prefs.getBoolean(profile+":useExternalApps",false));
        prefs.edit().putString(key,migrated).apply();
        return migrated;
    }
    public void displayMode(String profile,String mode){
        if(DisplayModePolicy.DOCKED.equals(mode)||DisplayModePolicy.DUAL.equals(mode))prefs.edit().putString(profile+":displayMode",mode).apply();
    }
    public String showDuoDashOn(String profile){return "external".equals(prefs.getString(profile+":showDuoDashOn","handheld"))?"external":"handheld";}
    public void showDuoDashOn(String profile,String display){if("handheld".equals(display)||"external".equals(display))prefs.edit().putString(profile+":showDuoDashOn",display).apply();}
    public String dockedCanvas(String profile,String displayIdentity,int nativeWidth,int nativeHeight){
        String key=profile+":dockedCanvas";
        String value=prefs.getString(key,null);
        if("tv_1080p".equals(value)||"tv_720p".equals(value)||"tv_4_3".equals(value))return value;
        String legacy=canvas(profile,displayIdentity);
        String migrated="ratio_4_3".equals(legacy)?"tv_4_3":"reduced".equals(legacy)?"tv_720p":DisplayModePolicy.defaultCanvas(nativeWidth,nativeHeight);
        prefs.edit().putString(key,migrated).apply();
        return migrated;
    }
    public void dockedCanvas(String profile,String optionId){
        if("tv_1080p".equals(optionId)||"tv_720p".equals(optionId)||"tv_4_3".equals(optionId))prefs.edit().putString(profile+":dockedCanvas",optionId).apply();
    }
    public boolean useExternalApps(String profile){return prefs.getBoolean(profile+":useExternalApps",false);}
    public void useExternalApps(String profile,boolean enabled){prefs.edit().putBoolean(profile+":useExternalApps",enabled).apply();}
    public boolean screenOffRequested(String profile){return prefs.getBoolean(profile+":screenOffRequested",false);}
    public boolean hasScreenOffRequested(String profile){return prefs.contains(profile+":screenOffRequested");}
    public void screenOffRequested(String profile,boolean requested){prefs.edit().putBoolean(profile+":screenOffRequested",requested).apply();}
    public void migrate(String identity) {
        String key=ControlLogic.outputKey(identity);
        if(exists(key))return;
        String old=identity.isEmpty()?"Handheld":prefs.getString("bind:"+identity,"");
        if(!old.isEmpty()&&exists(old))save(key,read(old,"main",80),read(old,"upper",100),read(old,"volume",40));
    }
    public boolean exists(String profile) { return prefs.contains(profile+":main"); }
    /** Seeds a missing output profile only when this is an eligible new installation. */
    public boolean seedNewProfile(String key) {
        if(!PortableDefaults.shouldSeedProfile(InstallDefaults.profileTemplateEnabled(context),exists(key)))return false;
        List<UiState.AppShortcut> apps=portableApps();
        if(SharedDashboardLayout.shouldSeedPortableDefaults(sharedLayoutInitialized(),!savedLayoutProfiles().isEmpty()))
            seedSharedDefaults(apps);
        prefs.edit()
            .putInt(key+":main",PortableDefaults.MAIN_BRIGHTNESS)
            .putInt(key+":upper",PortableDefaults.EXTERNAL_BRIGHTNESS)
            .putInt(key+":volume",PortableDefaults.MASTER_VOLUME)
            .putInt(key+":mainAudio",PortableDefaults.MAIN_AUDIO)
            .putInt(key+":upperAudio",PortableDefaults.EXTERNAL_AUDIO)
            .apply();
        return true;
    }

    /** Prepare a safe local layout source without requiring live display discovery. */
    public void prepareDashboardLayout() {
        if(sharedLayoutInitialized())return;
        String last=lastKnownLayoutProfile();
        if(last!=null&&savedLayoutProfiles().contains(last)) {
            initializeSharedLayout(last,"main");
            return;
        }
        initializeSharedLayoutFromUniqueCustomProfile("main");
    }

    /** Remember only the last successfully identified output; this key selects layout data only. */
    public void rememberLayoutProfile(String profileKey) {
        if(profileKey!=null&&(profileKey.equals("handheld")||profileKey.startsWith("output:")))
            prefs.edit().putString("lastLayoutProfile",profileKey).apply();
    }

    private String lastKnownLayoutProfile() {
        String profile=prefs.getString("lastLayoutProfile",null);
        return profile!=null&&(profile.equals("handheld")||profile.startsWith("output:"))?profile:null;
    }

    /** Loads only dashboard layout preferences, without requiring display status or applying hardware settings. */
    public void loadDashboardLayout(String key,UiState state,String fpsFallback) {
        if(state==null)return;
        state.layoutKey=key;
        // Do not normalize an absent legacy profile into the preference store:
        // fresh installs must remain eligible for the later portable-default seed.
        if(!sharedLayoutInitialized()&&!savedLayoutProfiles().contains(key)) {
            state.fpsScreen="upper".equals(fpsFallback)?"upper":"main";
            return;
        }
        state.controlOrder=order(key);
        state.hiddenControls=hidden(key);
        state.horizontalSliders=horizontal(key);
        state.compactControls=compact(key);
        state.actionColumns=actionColumns(key);
        state.smallControls=small(key);
        state.wideControls=wide(key);
        state.controlSizes=sizes(key);
        state.pinnedApps.clear();
        state.pinnedApps.addAll(pinned(key));
        String savedFps=fpsScreen(key);
        state.fpsScreen=savedFps==null?("upper".equals(fpsFallback)?"upper":"main"):savedFps;
    }

    /**
     * Explicit one-time migration for existing installs. The caller must select
     * the source profile after inspecting the user's saved layout. Legacy
     * per-output values are retained as a fallback and are never deleted.
     */
    public synchronized boolean initializeSharedLayout(String sourceProfileKey,String fpsScreenFallback) {
        if(sharedLayoutInitialized())return false;
        if(sourceProfileKey==null||!(sourceProfileKey.equals("handheld")||sourceProfileKey.startsWith("output:")))
            throw new IllegalArgumentException("Choose a saved output profile explicitly");
        if(!"main".equals(fpsScreenFallback)&&!"upper".equals(fpsScreenFallback))
            throw new IllegalArgumentException("Choose the current FPS screen explicitly");

        Map<String,String> source=new LinkedHashMap<>();
        source.put(SharedDashboardLayout.ORDER,prefs.getString(sourceProfileKey+":order",""));
        source.put(SharedDashboardLayout.HIDDEN,prefs.getString(sourceProfileKey+":hidden",""));
        source.put(SharedDashboardLayout.WIDE,prefs.getString(sourceProfileKey+":wide",""));
        source.put(SharedDashboardLayout.SMALL,prefs.getString(sourceProfileKey+":small",""));
        if(prefs.contains(sourceProfileKey+":"+SharedDashboardLayout.SIZES))source.put(SharedDashboardLayout.SIZES,sizes(sourceProfileKey));
        source.put(SharedDashboardLayout.ACTION_COLUMNS,Integer.toString(prefs.getInt(sourceProfileKey+":actionColumns",4)));
        source.put(SharedDashboardLayout.HORIZONTAL,Boolean.toString(prefs.getBoolean(sourceProfileKey+":horizontal",true)));
        source.put(SharedDashboardLayout.COMPACT,Boolean.toString(prefs.getBoolean(sourceProfileKey+":compact",true)));
        String fps=prefs.getString(sourceProfileKey+":fpsScreen",null);
        source.put(SharedDashboardLayout.FPS_SCREEN,"main".equals(fps)||"upper".equals(fps)?fps:fpsScreenFallback);
        source.put(SharedDashboardLayout.PINNED,prefs.getString(sourceProfileKey+":pinned","[]"));
        source.put(SharedDashboardLayout.LAYOUT_VERSION,Integer.toString(prefs.getInt(sourceProfileKey+":layoutVersion",0)));

        Map<String,String> current=Collections.singletonMap(
            SharedDashboardLayout.VERSION_KEY,Integer.toString(prefs.getInt(SharedDashboardLayout.VERSION_KEY,0)));
        SharedDashboardLayout.MigrationPlan plan=SharedDashboardLayout.planMigration(current,sourceProfileKey,source);
        if(plan.alreadyInitialized)return false;
        writeSharedLayout(plan);
        return true;
    }

    /** Automatically migrates only when saved layouts identify one safe source. */
    public synchronized boolean initializeSharedLayoutFromUniqueCustomProfile(String fpsScreenFallback) {
        if(sharedLayoutInitialized())return false;
        Set<String> profiles=savedLayoutProfiles();
        if(profiles.isEmpty())return false;
        Map<String,Map<String,String>> layouts=new LinkedHashMap<>();
        for(String profile:profiles) {
            if(!prefs.contains(profile+":"+SharedDashboardLayout.LAYOUT_VERSION))return false;
            int version=prefs.getInt(profile+":"+SharedDashboardLayout.LAYOUT_VERSION,0);
            if(version<PortableDefaults.LAYOUT_VERSION)return false;
            Map<String,String> layout=new LinkedHashMap<>();
            String order=ControlLogic.cleanOrder(prefs.getString(profile+":"+SharedDashboardLayout.ORDER,""));
            String hidden=prefs.getString(profile+":"+SharedDashboardLayout.HIDDEN,ControlCatalog.defaultHidden());
            layout.put(SharedDashboardLayout.ORDER,order);
            layout.put(SharedDashboardLayout.HIDDEN,ControlLogic.cleanHidden(hidden));
            layout.put(SharedDashboardLayout.WIDE,ControlLogic.cleanHidden(prefs.getString(profile+":"+SharedDashboardLayout.WIDE,"")));
            layout.put(SharedDashboardLayout.SMALL,small(profile));
            if(prefs.contains(profile+":"+SharedDashboardLayout.SIZES))layout.put(SharedDashboardLayout.SIZES,sizes(profile));
            layout.put(SharedDashboardLayout.ACTION_COLUMNS,Integer.toString(actionColumns(profile)));
            layout.put(SharedDashboardLayout.HORIZONTAL,Boolean.toString(horizontal(profile)));
            layout.put(SharedDashboardLayout.COMPACT,Boolean.toString(compact(profile)));
            String fps=fpsScreen(profile);
            layout.put(SharedDashboardLayout.FPS_SCREEN,fps==null?"":fps);
            layout.put(SharedDashboardLayout.PINNED,encodeApps(pinned(profile)));
            layout.put(SharedDashboardLayout.LAYOUT_VERSION,Integer.toString(version));
            layouts.put(profile,layout);
        }
        String selected=SharedDashboardLayout.selectUniqueCustomProfile(layouts);
        return selected!=null&&initializeSharedLayout(selected,fpsScreenFallback);
    }

    private Set<String> savedLayoutProfiles() {
        Map<String,?> all=prefs.getAll();
        Set<String> profiles=new LinkedHashSet<>();
        String[] layoutSuffixes={
            SharedDashboardLayout.ORDER,SharedDashboardLayout.HIDDEN,SharedDashboardLayout.WIDE,SharedDashboardLayout.SMALL,
            SharedDashboardLayout.SIZES,
            SharedDashboardLayout.ACTION_COLUMNS,SharedDashboardLayout.HORIZONTAL,
            SharedDashboardLayout.COMPACT,SharedDashboardLayout.FPS_SCREEN,
            SharedDashboardLayout.PINNED,SharedDashboardLayout.LAYOUT_VERSION
        };
        for(String storedKey:all.keySet())for(String suffix:layoutSuffixes) {
            String tail=":"+suffix;
            if(storedKey.endsWith(tail)) {
                String profile=storedKey.substring(0,storedKey.length()-tail.length());
                if(profile.equals("handheld")||profile.startsWith("output:"))profiles.add(profile);
                break;
            }
        }
        return profiles;
    }

    private void seedSharedDefaults(List<UiState.AppShortcut> apps) {
        Map<String,String> layout=new LinkedHashMap<>();
        String order=PortableDefaults.ORDER;
        for(UiState.AppShortcut app:apps)order+=","+app.id;
        layout.put(SharedDashboardLayout.ORDER,ControlLogic.cleanOrder(order));
        layout.put(SharedDashboardLayout.HIDDEN,ControlLogic.cleanHidden(PortableDefaults.HIDDEN));
        layout.put(SharedDashboardLayout.WIDE,PortableDefaults.WIDE_CONTROLS);
        layout.put(SharedDashboardLayout.SMALL,PortableDefaults.SMALL_CONTROLS);
        layout.put(SharedDashboardLayout.ACTION_COLUMNS,Integer.toString(PortableDefaults.ACTION_COLUMNS));
        layout.put(SharedDashboardLayout.HORIZONTAL,Boolean.toString(PortableDefaults.HORIZONTAL_SLIDERS));
        layout.put(SharedDashboardLayout.COMPACT,Boolean.toString(PortableDefaults.COMPACT_CONTROLS));
        layout.put(SharedDashboardLayout.FPS_SCREEN,PortableDefaults.FPS_SCREEN);
        layout.put(SharedDashboardLayout.PINNED,encodeApps(apps));
        layout.put(SharedDashboardLayout.LAYOUT_VERSION,Integer.toString(PortableDefaults.LAYOUT_VERSION));
        Map<String,String> current=Collections.singletonMap(
            SharedDashboardLayout.VERSION_KEY,Integer.toString(prefs.getInt(SharedDashboardLayout.VERSION_KEY,0)));
        writeSharedLayout(SharedDashboardLayout.planDefaults(current,layout));
    }

    private boolean sharedLayoutInitialized() {
        return prefs.getInt(SharedDashboardLayout.VERSION_KEY,0)>=SharedDashboardLayout.VERSION;
    }

    private void writeSharedLayout(SharedDashboardLayout.MigrationPlan plan) {
        SharedPreferences.Editor edit=prefs.edit();
        for(Map.Entry<String,String> entry:plan.writes.entrySet()) {
            if(entry.getKey().equals(SharedDashboardLayout.VERSION_KEY)
                ||entry.getKey().equals(SharedDashboardLayout.sharedFieldKey(SharedDashboardLayout.ACTION_COLUMNS))
                ||entry.getKey().equals(SharedDashboardLayout.sharedFieldKey(SharedDashboardLayout.LAYOUT_VERSION)))
                edit.putInt(entry.getKey(),Integer.parseInt(entry.getValue()));
            else if(entry.getKey().equals(SharedDashboardLayout.sharedFieldKey(SharedDashboardLayout.HORIZONTAL))
                ||entry.getKey().equals(SharedDashboardLayout.sharedFieldKey(SharedDashboardLayout.COMPACT)))
                edit.putBoolean(entry.getKey(),Boolean.parseBoolean(entry.getValue()));
            else edit.putString(entry.getKey(),entry.getValue());
        }
        edit.apply();
    }

    private String layoutStorageKey(String profile,String field) {
        return SharedDashboardLayout.storageKey(profile,field,sharedLayoutInitialized());
    }
    private String layoutStorageProfile(String profile) {
        return SharedDashboardLayout.storageProfile(profile,sharedLayoutInitialized());
    }
    private List<UiState.AppShortcut> portableApps() {
        List<UiState.AppShortcut> result=new ArrayList<>();
        android.content.pm.PackageManager pm=context.getPackageManager();
        for(PortableDefaults.App app:PortableDefaults.apps()) {
            android.content.ComponentName component=android.content.ComponentName.unflattenFromString(app.component);
            if(component==null)continue;
            try { pm.getActivityInfo(component,0); }
            catch(android.content.pm.PackageManager.NameNotFoundException missingActivity) {
                android.content.Intent launch=pm.getLaunchIntentForPackage(app.packageName);
                if(launch==null||launch.getComponent()==null)continue;
                component=launch.getComponent();
            }
            String flattened=component.flattenToString();
            result.add(new UiState.AppShortcut(AppPicker.idFor(flattened+":"+app.external),app.label,flattened,app.external));
        }
        return result;
    }
    public int read(String profile,String key,int fallback) {
        return ControlLogic.percent(prefs.getInt(profile+":"+key,fallback),key.equals("main"));
    }
    public String order(String key){String storage=layoutStorageProfile(key);upgradeLayout(storage);return ControlLogic.cleanOrder(prefs.getString(storage+":"+SharedDashboardLayout.ORDER,ControlLogic.DEFAULT_ORDER));}
    public String hidden(String key){String storage=layoutStorageProfile(key);upgradeLayout(storage);return ControlLogic.cleanHidden(prefs.getString(storage+":"+SharedDashboardLayout.HIDDEN,ControlCatalog.defaultHidden()));}
    private void upgradeLayout(String key){
        String originalOrder=prefs.getString(key+":order",null);
        if(prefs.getInt(key+":layoutVersion",0)<4){
            String oldOrder=prefs.getString(key+":order",null),oldHidden=prefs.getString(key+":hidden",null);
            String oldFpsScreen=ControlLogic.legacyFpsSelection(oldOrder,oldHidden);
            String[] layout=ControlLogic.upgradeLayout(oldOrder,oldHidden);
            SharedPreferences.Editor edit=prefs.edit().putString(key+":order",layout[0]).putString(key+":hidden",layout[1]).putInt(key+":layoutVersion",4);
            if(oldFpsScreen!=null&&!prefs.contains(key+":fpsScreen"))edit.putString(key+":fpsScreen",oldFpsScreen);edit.apply();
        }
        if(prefs.getInt(key+":layoutVersion",0)<5){
            String order=prefs.getString(key+":order",ControlCatalog.defaultOrder()),hidden=prefs.getString(key+":hidden",ControlCatalog.defaultHidden());
            Set<String> removed=new LinkedHashSet<>(Arrays.asList(hidden.split(",")));Set<String> shown=new HashSet<>();
            for(String id:order.split(","))if(ControlLogic.action(id)&&!removed.contains(id))shown.add(id);
            if(shown.equals(new HashSet<>(Arrays.asList("swap","up","down","wifi","bluetooth","rotation","apps","fan","performance","charging","controller_style","trigger_mode"))))removed.addAll(Arrays.asList("rotation","controller_style","trigger_mode"));
            prefs.edit().putString(key+":hidden",ControlLogic.cleanHidden(String.join(",",removed))).putInt(key+":layoutVersion",5).apply();
        }
        if(prefs.getInt(key+":layoutVersion",0)<6){
            String order=prefs.getString(key+":order",ControlLogic.DEFAULT_ORDER),hidden=prefs.getString(key+":hidden",ControlCatalog.defaultHidden());
            String oldSelection=ControlLogic.legacyFpsSelection(order,hidden);String[] layout=ControlLogic.mergeLegacyFps(order,hidden);
            SharedPreferences.Editor edit=prefs.edit().putString(key+":order",layout[0]).putString(key+":hidden",ControlLogic.cleanHidden(layout[1])).putInt(key+":layoutVersion",6);
            if(!prefs.contains(key+":fpsScreen")&&oldSelection!=null)edit.putString(key+":fpsScreen",oldSelection);edit.apply();
        }
        if(prefs.getInt(key+":layoutVersion",0)<7){
            String hidden=ControlLogic.hideMissingOptionalControls(originalOrder,
                    prefs.getString(key+":hidden",ControlCatalog.defaultHidden()),"clear_recents","audio_output","controller_mouse","quick_settings");
            prefs.edit().putString(key+":hidden",hidden).putInt(key+":layoutVersion",7).apply();
        }
        if(prefs.getInt(key+":layoutVersion",0)<8){
            String hidden=ControlLogic.hideMissingOptionalControls(originalOrder,
                    prefs.getString(key+":hidden",ControlCatalog.defaultHidden()),"quick_panel");
            prefs.edit().putString(key+":hidden",hidden).putInt(key+":layoutVersion",8).apply();
        }
    }
    public String wide(String key){return ControlLogic.cleanHidden(prefs.getString(layoutStorageKey(key,SharedDashboardLayout.WIDE),""));}
    public void wide(String key,String value){prefs.edit().putString(layoutStorageKey(key,SharedDashboardLayout.WIDE),ControlLogic.cleanHidden(value)).apply();}
    public String small(String key){return ControlLogic.cleanHidden(prefs.getString(layoutStorageKey(key,SharedDashboardLayout.SMALL),""));}
    public String sizes(String key){return ControlSizePolicy.normalizeSizes(prefs.getString(layoutStorageKey(key,SharedDashboardLayout.SIZES),""));}
    public void sizes(String key,String value){prefs.edit().putString(layoutStorageKey(key,SharedDashboardLayout.SIZES),ControlSizePolicy.normalizeSizes(value)).apply();}
    public void actionSizes(String key,String small,String wide){prefs.edit().putString(layoutStorageKey(key,SharedDashboardLayout.SMALL),ControlLogic.cleanHidden(small)).putString(layoutStorageKey(key,SharedDashboardLayout.WIDE),ControlLogic.cleanHidden(wide)).apply();}
    public void actionSizes(String key,String small,String wide,String explicit){prefs.edit().putString(layoutStorageKey(key,SharedDashboardLayout.SMALL),ControlLogic.cleanHidden(small)).putString(layoutStorageKey(key,SharedDashboardLayout.WIDE),ControlLogic.cleanHidden(wide)).putString(layoutStorageKey(key,SharedDashboardLayout.SIZES),ControlSizePolicy.normalizeSizes(explicit)).apply();}
    public int audio(String key,boolean upper){return ControlLogic.percent(prefs.getInt(key+(upper?":upperAudio":":mainAudio"),100),false);}
    public int refresh(String key){int rate=prefs.getInt(key+":refreshHz",0);return rate>=24&&rate<=240?rate:0;}
    public void refresh(String key,int rate){if(rate>=24&&rate<=240)prefs.edit().putInt(key+":refreshHz",rate).apply();}
    public void audio(String key,int main,int upper){
        SharedPreferences.Editor edit=prefs.edit().putInt(key+":mainAudio",main).putInt(key+":upperAudio",upper);
        if(main>0)edit.putInt(key+":mainAudioLast",ControlLogic.percent(main,false));
        if(upper>0)edit.putInt(key+":upperAudioLast",ControlLogic.percent(upper,false));
        edit.apply();
    }
    public boolean audioMuted(String key,boolean upper){return prefs.getBoolean(key+(upper?":upperAudioMuted":":mainAudioMuted"),false);}
    public void audioMuted(String key,boolean upper,boolean muted){
        String gainKey=key+(upper?":upperAudio":":mainAudio"),lastKey=key+(upper?":upperAudioLast":":mainAudioLast");
        SharedPreferences.Editor edit=prefs.edit();
        if(muted){int current=ControlLogic.percent(prefs.getInt(gainKey,100),false);if(current>0)edit.putInt(lastKey,current);}
        edit.putBoolean(key+(upper?":upperAudioMuted":":mainAudioMuted"),muted).apply();
    }
    public int audioRestore(String key,boolean upper){
        String lastKey=key+(upper?":upperAudioLast":":mainAudioLast");
        int fallback=audio(key,upper);if(fallback<=0)fallback=100;
        return ControlLogic.percent(prefs.getInt(lastKey,fallback),false);
    }
    public List<UiState.AppShortcut> pinned(String key){
        List<UiState.AppShortcut> result=new ArrayList<>();try{JSONArray list=new JSONArray(prefs.getString(layoutStorageKey(key,SharedDashboardLayout.PINNED),"[]"));for(int i=0;i<list.length();i++){JSONObject app=list.getJSONObject(i);if(ControlCatalog.isApp(app.getString("id")))result.add(new UiState.AppShortcut(app.getString("id"),app.getString("label"),app.getString("component"),app.optBoolean("upper")));}}catch(JSONException ignored){}return result;
    }
    public void pinned(String key,List<UiState.AppShortcut> apps){prefs.edit().putString(layoutStorageKey(key,SharedDashboardLayout.PINNED),encodeApps(apps)).apply();}
    public void replaceLayout(String key,String order,String hidden,String wide,List<UiState.AppShortcut> apps){prefs.edit().putString(layoutStorageKey(key,SharedDashboardLayout.ORDER),ControlLogic.cleanOrder(order)).putString(layoutStorageKey(key,SharedDashboardLayout.HIDDEN),ControlLogic.cleanHidden(hidden)).putString(layoutStorageKey(key,SharedDashboardLayout.WIDE),ControlLogic.cleanHidden(wide)).putString(layoutStorageKey(key,SharedDashboardLayout.PINNED),encodeApps(apps)).apply();}
    public void restoreEditLayout(String key,DashboardEditSnapshot snapshot){
        if(snapshot==null||!key.equals(snapshot.profileKey))return;
        prefs.edit()
            .putString(layoutStorageKey(key,SharedDashboardLayout.ORDER),ControlLogic.cleanOrder(snapshot.order))
            .putString(layoutStorageKey(key,SharedDashboardLayout.HIDDEN),ControlLogic.cleanHidden(snapshot.hidden))
            .putString(layoutStorageKey(key,SharedDashboardLayout.WIDE),ControlLogic.cleanHidden(snapshot.wide))
            .putString(layoutStorageKey(key,SharedDashboardLayout.SMALL),ControlLogic.cleanHidden(snapshot.small))
            .putString(layoutStorageKey(key,SharedDashboardLayout.SIZES),ControlSizePolicy.normalizeSizes(snapshot.sizes))
            .putString(layoutStorageKey(key,SharedDashboardLayout.PINNED),encodeApps(snapshot.copyPinnedApps()))
            .apply();
    }
    /** Drops hidden app shortcuts left by earlier versions while retaining active shortcuts and built-in hidden controls. */
    public void cleanHiddenApps(String key){
        List<UiState.AppShortcut> apps=pinned(key);List<String> ids=new ArrayList<>();for(UiState.AppShortcut app:apps)ids.add(app.id);
        String order=order(key),hidden=hidden(key),wide=wide(key);
        ControlLogic.AppControlState cleaned=ControlLogic.cleanAppControls(order,hidden,wide,ids,null);
        String small=small(key),cleanSmall=ControlSizePolicy.retainSmall(small,cleaned.pinnedAppIds);
        String sizes=sizes(key),cleanSizes=ControlSizePolicy.retainSizes(sizes,cleaned.pinnedAppIds);
        if(!small.equals(cleanSmall)||!sizes.equals(cleanSizes))actionSizes(key,cleanSmall,wide,cleanSizes);
        if(!order.equals(cleaned.order)||!hidden.equals(cleaned.hidden)||!wide.equals(cleaned.wide)||!ids.equals(cleaned.pinnedAppIds)){
            List<UiState.AppShortcut> retained=new ArrayList<>();Set<String> keep=new LinkedHashSet<>(cleaned.pinnedAppIds);for(UiState.AppShortcut app:apps)if(keep.remove(app.id))retained.add(app);
            replaceLayout(key,cleaned.order,cleaned.hidden,cleaned.wide,retained);
        }
    }
    private String encodeApps(List<UiState.AppShortcut> apps){JSONArray result=new JSONArray();for(UiState.AppShortcut app:apps)try{result.put(new JSONObject().put("id",app.id).put("label",app.label).put("component",app.component).put("upper",app.upper));}catch(JSONException ignored){}return result.toString();}
    public boolean horizontal(String key){return prefs.getBoolean(layoutStorageKey(key,SharedDashboardLayout.HORIZONTAL),true);}
    public boolean compact(String key){return prefs.getBoolean(layoutStorageKey(key,SharedDashboardLayout.COMPACT),true);}
    public void style(String key,boolean horizontal,boolean compact){prefs.edit().putBoolean(layoutStorageKey(key,SharedDashboardLayout.HORIZONTAL),horizontal).putBoolean(layoutStorageKey(key,SharedDashboardLayout.COMPACT),compact).apply();}
    public int actionColumns(String key){return ControlLogic.actionColumns(prefs.getInt(layoutStorageKey(key,SharedDashboardLayout.ACTION_COLUMNS),4));}
    public void actionColumns(String key,int columns){prefs.edit().putInt(layoutStorageKey(key,SharedDashboardLayout.ACTION_COLUMNS),ControlLogic.actionColumns(columns)).apply();}
    public String fpsScreen(String key){String value=prefs.getString(layoutStorageKey(key,SharedDashboardLayout.FPS_SCREEN),null);return "main".equals(value)||"upper".equals(value)?value:null;}
    public void fpsScreen(String key,String screen){if("main".equals(screen)||"upper".equals(screen))prefs.edit().putString(layoutStorageKey(key,SharedDashboardLayout.FPS_SCREEN),screen).apply();}
    public String canvas(String profile,String displayIdentity){return prefs.getString(profile+":canvas:"+safeIdentity(displayIdentity),null);}
    public void canvas(String profile,String displayIdentity,String optionId){
        if(optionId==null||!optionId.matches("native|reduced|ratio_4_3|ratio_16_9"))return;
        prefs.edit().putString(profile+":canvas:"+safeIdentity(displayIdentity),optionId).apply();
    }
    private static String safeIdentity(String identity){return identity==null?"":identity.replaceAll("[^A-Za-z0-9_.:-]","_");}
    public void layout(String key,String order,String hidden){prefs.edit().putString(layoutStorageKey(key,SharedDashboardLayout.ORDER),ControlLogic.cleanOrder(order)).putString(layoutStorageKey(key,SharedDashboardLayout.HIDDEN),ControlLogic.cleanHidden(hidden)).apply();}
    public void save(String profile,int main,int upper,int volume) {
        prefs.edit().putInt(profile+":main",ControlLogic.percent(main,true))
            .putInt(profile+":upper",ControlLogic.percent(upper,false))
            .putInt(profile+":volume",ControlLogic.percent(volume,false)).apply();
    }
}
