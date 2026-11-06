package dev.nova.deck;

import android.app.ActivityManager;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.media.AudioManager;
import android.os.*;
import android.provider.Settings;
import android.view.Display;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** UI-thread state owner. All privileged IPC is serialized off the UI thread. */
public final class DeckController implements DashboardActions, DisplayManager.DisplayListener {
    public interface Host {
        void render(UiState state); void closePanel(); void showSetup(); void showEditor(); void editControl(String id);
        void promptOutputName(String identity,String suggestion,boolean rename,java.util.function.Consumer<String> result);
        void performControl(String id); void displayTargetChanged(); void openConnectionSettings(); void openControlSettings(String id);
        void networkAvailable(); void networkUnavailable();
        void message(String text);
    }
    public final UiState state=new UiState();
    private final Context context;
    private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ProfileStore profiles;
    private final BatteryRuntimeEstimator batteryRuntime=new BatteryRuntimeEstimator();
    private final BridgeClient bridge;
    private final AudioManager audio;
    private final DisplayManager displays;
    private final ConnectivityManager connectivity;
    private final Set<Network> wifiNetworks=Collections.synchronizedSet(new HashSet<>());
    private int externalId=-1;
    private String identity="";
    private String profileKey="handheld";
    private final Set<String> offeredNames=new HashSet<>();
    private String previousUpperApp="";
    private boolean running=true, panelOpen, pollInFlight, stopping, panelPowerApplied;
    private boolean panelPowerRequestPending,pendingPanelPowerOff;
    private long panelPowerSequence;
    private String temporaryWakePackage="";
    private long temporaryWakeDeadline;
    private boolean temporaryWakeSeen;
    private List<ControlLogic.Task> lastTasks=Collections.emptyList();
    private long lastEdit;
    private long lastSample;
    private Runnable mainChange,upperChange;
    private String configuredShortcuts;
    private String canvasApplySignature="";
    private String dockedCanvasApplySignature="";
    private UiState.ResolutionPreview pendingResolution;
    private final Runnable windowPoll=this::poll;
    private final ConnectivityManager.NetworkCallback wifiCallback=new ConnectivityManager.NetworkCallback(){
        @Override public void onAvailable(Network network){wifiNetworks.add(network);setWifiConnected(!wifiNetworks.isEmpty());}
        @Override public void onLost(Network network){wifiNetworks.remove(network);setWifiConnected(!wifiNetworks.isEmpty());}
        @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){
            if(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))wifiNetworks.add(network);else wifiNetworks.remove(network);
            setWifiConnected(!wifiNetworks.isEmpty());
        }
    };
    private final Runnable pollTick=new Runnable() {
        public void run() { if(!running)return; poll(); main.postDelayed(this,panelOpen?1500:10000); }
    };

    public DeckController(Context context,Host host) {
        this.context=context; this.host=host;
        InstallDefaults.initialize(context);
        profiles=new ProfileStore(context); bridge=new BridgeClient(context);
        // The dashboard layout is local user data. Resolve a safe shared migration
        // source and render it before the first helper poll can succeed or fail.
        profiles.prepareDashboardLayout();
        profiles.loadDashboardLayout(profileKey,state,"main");
        android.content.SharedPreferences uiPrefs=context.getSharedPreferences("ui",Context.MODE_PRIVATE);
        state.showBatteryTime=uiPrefs.getBoolean("showBatteryTime",true);
        state.showHeaderHz=uiPrefs.getBoolean("showHeaderHz",true);
        state.showHeaderWifi=uiPrefs.getBoolean("showHeaderWifi",true);
        state.showHeaderClock=uiPrefs.getBoolean("showHeaderClock",true);
        state.cpuMetricMode=MetricModePolicy.normalize(uiPrefs.getString(MetricModePolicy.CPU_PREF,MetricModePolicy.USAGE));
        state.gpuMetricMode=MetricModePolicy.normalize(uiPrefs.getString(MetricModePolicy.GPU_PREF,MetricModePolicy.USAGE));
        audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        displays=(DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
        connectivity=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(connectivity!=null)try{
            NetworkRequest wifiRequest=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            connectivity.registerNetworkCallback(wifiRequest,wifiCallback);
        }catch(Exception ignored){}
        state.volume=currentVolume();state.volumeMuted=audio.isStreamMute(AudioManager.STREAM_MUSIC);
        if(state.volumeMuted)state.volume=profiles.read(profileKey,"volume",state.volume);
        try { state.main=ControlLogic.percent(Math.round(Settings.System.getInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS)*100f/255),true); }
        catch(Exception ignored) { }
        state.profile="Handheld";
        state.displayMode=profiles.displayMode(profileKey);
        state.showDuoDashOnExternal=false;
        displays.registerDisplayListener(this,main);
        main.post(pollTick);
    }
    public void panelOpen(boolean open) {
        panelOpen=open;
        if(open){temporaryWakePackage="";temporaryWakeSeen=false;temporaryWakeDeadline=0;if(!panelTargetsExternal())requestPanelPower("wake",false,true);}
        else if(!stopping)syncPanelPower();
        applyUpper();
        if(open)poll();
    }
    public void windowChanged(){main.removeCallbacks(windowPoll);main.postDelayed(windowPoll,250);}
    public void controllerFocus(boolean enabled){
        if(!running||!state.bridgeReady)return;
        int display=panelWindowDisplayId();
        io.execute(()->{
            try{JSONObject result=bridge.request("panel_focus",new JSONObject().put("enabled",enabled).put("display",display));
                android.util.Log.i("NovaDeck","Controller focus: "+result);}
            catch(Exception error){android.util.Log.w("NovaDeck","Controller focus unavailable",error);}
        });
    }
    private void render() { sample(); host.render(state); }
    private int streamVolumeMinimum(){return Build.VERSION.SDK_INT>=Build.VERSION_CODES.P?audio.getStreamMinVolume(AudioManager.STREAM_MUSIC):0;}
    private int streamVolumeMaximum(){return audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);}
    private int currentVolume() { return VolumeStepPolicy.percentForIndex(audio.getStreamVolume(AudioManager.STREAM_MUSIC),streamVolumeMinimum(),streamVolumeMaximum()); }
    private void save() {
        profiles.save(profileKey,state.main,state.upper,state.volume);
        state.feedback=state.profile.equals("New output")?"Settings saved":"Saved for "+state.profile;
    }
    private JSONObject target() throws JSONException { return new JSONObject().put("display",externalId).put("identity",identity); }
    private void request(String command,JSONObject arguments,boolean action) {
        if(!running)return;
        final String requestProfile=profileKey;
        if(action) {state.busy=true;render();}
        io.execute(()->{
            try {
                JSONObject result=bridge.request(command,arguments);
                main.post(()->{
                    if(!running)return;
                    if(command.equals("setting")&&arguments!=null&&arguments.optString("id").equals("refresh_mode"))profiles.refresh(requestProfile,arguments.optInt("value"));
                    if(action) { state.busy=false;state.feedback=result.optString("message","");android.util.Log.i("NovaDeck","Control completed: "+command); }
                    render();
                    if(action) { poll(); applyUpper(); }
                });
            } catch(Exception error) {
                main.post(()->{
                    if(!running)return;
                    if(command.equals("canvas_apply"))canvasApplySignature="";
                    if(command.equals("dock_canvas"))dockedCanvasApplySignature="";
                    if(action)state.busy=false;
                    state.feedback=error.getMessage()==null?"Control unavailable":error.getMessage();
                    if(action){host.message(state.feedback);android.util.Log.w("NovaDeck","Control failed: "+command+" "+state.feedback);}
                    render();
                });
            }
        });
    }
    private void poll() {
        if(!running||pollInFlight)return;
        pollInFlight=true;
        io.execute(()->{
            BridgeStatusPoll.Result<JSONObject> result=bridge.pollStatus();
            main.post(()->{
                pollInFlight=false;if(!running)return;
                if(result.status!=null){acceptStatus(result.status);return;}
                // A status failure is not proof that the local helper stopped. Keep
                // cached state and usable controls when its independent probe succeeds.
                android.util.Log.w("NovaDeck","Status unavailable: "+result.failure.getClass().getSimpleName()
                    +"; helperHealthy="+result.helperReady);
                state.bridgeReady=result.helperReady;
                if(result.helperReady){
                    state.connection="Connected; retrying status";
                }else{
                    state.connection="System controls need reconnecting";
                    canvasApplySignature="";
                    state.panelPowerSupported=false;panelPowerApplied=false;
                    configuredShortcuts=null;if(DeckAccessibilityService.connected!=null)DeckAccessibilityService.connected.nativeGamepad=false;
                    context.sendBroadcast(new Intent(MirrorActivity.CLOSE).setPackage(context.getPackageName()));
                    state.externalConnected=Arrays.stream(displays.getDisplays()).anyMatch(d->d.getDisplayId()!=0);
                }
                render();
            });
        });
    }

    private void acceptStatus(JSONObject status) {
        try {
            int previousDashboardTarget=dashboardDisplayId();
            boolean previousPanelExternal=panelTargetsExternal();
            state.volumeMuted=audio.isStreamMute(AudioManager.STREAM_MUSIC);
            boolean wasReady=state.bridgeReady;
            state.bridgeReady=true;
            if(DeckAccessibilityService.connected!=null)DeckAccessibilityService.connected.nativeGamepad=status.optBoolean("gamepadMonitor");
            configureShortcuts();
            JSONArray screens=status.getJSONArray("displays");
            JSONObject upper=null;
            for(int i=0;i<screens.length();i++) {
                JSONObject screen=screens.getJSONObject(i);
                if(screen.getInt("id")==0) state.refreshHz=(float)screen.optDouble("refresh",60);
                else if(screen.optInt("type")==2&&upper==null) upper=screen;
            }
            String nextIdentity=upper==null?"":upper.getString("identity");
            int nextId=upper==null?-1:upper.getInt("id");
            boolean changed=!nextIdentity.equals(identity)||nextId!=externalId||(!wasReady);
            state.externalConnected=upper!=null;
            if(changed){panelPowerSequence++;panelPowerRequestPending=false;panelPowerApplied=status.optInt("panelPowerValue",-1)==1;temporaryWakePackage="";temporaryWakeSeen=false;temporaryWakeDeadline=0;dockedCanvasApplySignature="";}
            externalId=nextId;
            identity=nextIdentity;
            state.deviceName=upper==null?"Handheld":friendlyDevice(upper);
            state.connection=upper==null?"Ready for an external display":"Dual-screen controls connected";
            if(changed) {
                if(pendingResolution!=null){revertResolution(pendingResolution.transactionId,null);pendingResolution=null;}
                state.upperBlack=false;
                if(wasReady){
                    if(SystemClock.uptimeMillis()-lastEdit>1200)state.main=ControlLogic.percent(status.optInt("main",state.main),true);
                    if(!state.volumeMuted)state.volume=currentVolume();
                    profiles.save(profileKey,state.main,state.upper,state.volume);
                }
                profileKey=ControlLogic.outputKey(identity);
                state.layoutKey=profileKey;
                profiles.migrate(identity);
                profiles.initializeSharedLayoutFromUniqueCustomProfile(upper!=null?"upper":"main");
                profiles.seedNewProfile(profileKey);
                profiles.cleanHiddenApps(profileKey);
                profiles.rememberLayoutProfile(profileKey);
                String savedName=profiles.name(identity);
                state.profile=identity.isEmpty()?"Handheld":savedName==null?"New output":savedName;
                state.displayMode=profiles.displayMode(profileKey);
                state.useExternalApps=DisplayModePolicy.isDocked(state.displayMode);
                state.showDuoDashOnExternal=upper!=null&&"external".equals(profiles.showDuoDashOn(profileKey));
                boolean hasSavedScreenOff=profiles.hasScreenOffRequested(profileKey);
                state.screenOffRequested=TvProfilePolicy.initialScreenOffChoice(upper!=null,hasSavedScreenOff,profiles.screenOffRequested(profileKey),status.optBoolean("panelPowerNativeBaselineOff"));
                if(upper!=null&&!hasSavedScreenOff)profiles.screenOffRequested(profileKey,state.screenOffRequested);
                profiles.loadDashboardLayout(profileKey,state,upper!=null?"upper":"main");
                state.mainAudioMuted=profiles.audioMuted(profileKey,false);state.upperAudioMuted=profiles.audioMuted(profileKey,true);
                state.mainAudio=state.mainAudioMuted?0:profiles.audio(profileKey,false);state.upperAudio=state.upperAudioMuted?0:profiles.audio(profileKey,true);
                if(profiles.exists(profileKey)) {
                    state.main=profiles.read(profileKey,"main",80);
                    state.upper=profiles.read(profileKey,"upper",100);
                    state.volume=profiles.read(profileKey,"volume",state.volumeMuted?state.volume:currentVolume());
                } else {
                    state.main=ControlLogic.percent(status.optInt("main",state.main),true);
                    state.upper=100;if(!state.volumeMuted)state.volume=currentVolume();save();
                }
                previousUpperApp="";
                state.feedback="Settings save automatically";
                applyProfile();
                if(!identity.isEmpty()&&savedName==null&&offeredNames.add(identity))promptName(false);
            } else if(SystemClock.uptimeMillis()-lastEdit>1200) {
                int currentMain=ControlLogic.percent(status.optInt("main",state.main),true), currentVolume=state.volumeMuted?state.volume:currentVolume();
                if(currentMain!=state.main||(!state.volumeMuted&&currentVolume!=state.volume)){
                    state.main=currentMain;if(!state.volumeMuted)state.volume=currentVolume;
                    profiles.save(profileKey,state.main,state.upper,state.volume);
                }
            }
            updateDisplayCanvases(screens);
            List<ControlLogic.Task> taskList=new ArrayList<>();
            JSONArray tasks=status.getJSONArray("tasks");
            for(int i=0;i<tasks.length();i++) {
                JSONObject task=tasks.getJSONObject(i);
                taskList.add(new ControlLogic.Task(task.getInt("id"),task.getInt("display"),task.getString("package"),task.getBoolean("visible"),task.getBoolean("standard")));
            }
            lastTasks=taskList;
            JSONObject dockedStatus=status.optJSONObject("dockedCanvas");
            if(!useExternalApps()&&dockedStatus!=null&&dockedStatus.optBoolean("active")&&identity.equals(dockedStatus.optString("identity")))
                try{request("dock_canvas",target().put("action","release"),false);}catch(Exception ignored){}
            applySavedCanvases(screens);
            ControlLogic.Task lower=ControlLogic.foreground(taskList,0), top=ControlLogic.foreground(taskList,externalId);
            state.mainApp=lower==null?"Home":appName(lower.pkg);
            state.upperApp=top==null?(upper==null?"Not connected":"Ready for an app"):appName(top.pkg);
            if(top!=null&&!top.pkg.equals(previousUpperApp)) { previousUpperApp=top.pkg; applyUpper(); }
            // A helper heartbeat timeout removes dim layers for recovery; restore the saved value.
            else if(!changed&&upper!=null&&upper.optInt("dimPercent",100)!=effectiveUpperDimPercent()&&SystemClock.uptimeMillis()-lastEdit>1200)applyUpper();
            JSONObject controls=status.optJSONObject("controls");
            state.controls.clear();
            if(controls!=null)for(Iterator<String> keys=controls.keys();keys.hasNext();){String id=keys.next();JSONObject value=controls.getJSONObject(id);UiState.ControlStatus item=new UiState.ControlStatus(value.optBoolean("available"),value.optBoolean("active"),value.optString("detail"));item.value=value.optInt("value");JSONArray options=value.optJSONArray("options");if(options!=null)for(int index=0;index<options.length();index++){item.optionLabels.add(options.getJSONObject(index).getString("label"));item.optionValues.add(options.getJSONObject(index).getInt("value"));}state.controls.put(id,item);}
            JSONObject telemetry=status.optJSONObject("telemetry");if(telemetry!=null){state.cpuGhz=(float)telemetry.optDouble("cpuGhz",Double.NaN);state.gpuMhz=(float)telemetry.optDouble("gpuMhz",Double.NaN);state.fanPercent=(float)telemetry.optDouble("fanPercent",Double.NaN);state.cpuTemperatureC=(float)telemetry.optDouble("cpuTemperature",Double.NaN);state.cpuLoadPercent=(float)telemetry.optDouble("cpuLoadPercent",Double.NaN);state.gpuLoadPercent=(float)telemetry.optDouble("gpuLoadPercent",Double.NaN);state.cpuLoadReason=telemetry.optString("cpuLoadReason","");state.gpuLoadReason=telemetry.optString("gpuLoadReason","");}
            JSONObject audioStatus=status.optJSONObject("audio");if(audioStatus!=null){state.perAppAudioAvailable=audioStatus.optBoolean("supported");state.mainAudioAvailable=audioStatus.optBoolean("mainAvailable");state.upperAudioAvailable=audioStatus.optBoolean("upperAvailable");state.mainAudioReason=audioStatus.optString("mainReason","No active audio");state.upperAudioReason=audioStatus.optString("upperReason","No active audio");}
            if(DisplayModePolicy.isDocked(state.displayMode)){state.upperAudioAvailable=false;state.upperAudioReason="Mirrored audio";}
            JSONObject frames=status.optJSONObject("fps");JSONObject mainFrames=frames==null?null:frames.optJSONObject("main"),upperFrames=frames==null?null:frames.optJSONObject("upper");
            state.mainFps=mainFrames==null?Float.NaN:(float)mainFrames.optDouble("fps",Double.NaN);state.upperFps=upperFrames==null?Float.NaN:(float)upperFrames.optDouble("fps",Double.NaN);
            state.mainFpsReason=mainFrames==null?"Frame statistics unavailable":mainFrames.optString("reason");state.upperFpsReason=upperFrames==null?"Frame statistics unavailable":upperFrames.optString("reason");
            if(DisplayModePolicy.isDocked(state.displayMode)){state.upperFps=state.mainFps;state.upperFpsReason=state.mainFpsReason;}
            state.panelPowerSupported=status.optBoolean("panelPowerSupported");
            syncPanelPower();
            if(changed||previousDashboardTarget!=dashboardDisplayId()||previousPanelExternal!=panelTargetsExternal())host.displayTargetChanged();
            render();
        } catch(Exception error) { state.feedback="Display information changed; reconnecting";render(); }
    }
    private void applySavedCanvases(JSONArray screens){
        String signature=profileKey+"|"+identity+"|"+externalId+"|"+state.displayMode;
        JSONObject externalScreen=null;
        for(int i=0;i<screens.length();i++)try{JSONObject item=screens.getJSONObject(i);if(item.optInt("type")==2){externalScreen=item;break;}}catch(JSONException ignored){}
        if(DisplayModePolicy.shouldUseMirroredPrimary(DisplayModePolicy.isDocked(state.displayMode),externalScreen!=null)&&externalScreen!=null){
            if(!signature.equals(dockedCanvasApplySignature))try{
                String option=profiles.dockedCanvas(profileKey,identity,externalScreen.optInt("nativeWidth"),externalScreen.optInt("nativeHeight"));
                request("dock_canvas",target().put("action","apply").put("optionId",option),false);
                dockedCanvasApplySignature=signature;
            }catch(Exception ignored){}
            if(signature.equals(canvasApplySignature))return;
            canvasApplySignature=signature;
            return;
        }else if(externalScreen!=null&&!dockedCanvasApplySignature.isEmpty()){
            try{request("dock_canvas",target().put("action","release"),false);}catch(Exception ignored){}
            dockedCanvasApplySignature="";
        }
        if(signature.equals(canvasApplySignature))return;
        canvasApplySignature=signature;
        for(int i=0;i<screens.length();i++)try{
            JSONObject screen=screens.getJSONObject(i);int displayId=screen.getInt("id");
            String displayIdentity=screen.getString("identity");
            String choice=profiles.canvas(profileKey,displayIdentity);
            if(choice==null)choice="native";
            request("canvas_apply",new JSONObject().put("display",displayId).put("identity",displayIdentity).put("optionId",choice),false);
        }catch(Exception ignored){}
    }
    private void updateDisplayCanvases(JSONArray screens)throws JSONException{
        state.displayCanvases.clear();JSONObject mainScreen=null,externalScreen=null;
        for(int i=0;i<screens.length();i++){JSONObject item=screens.getJSONObject(i);if(item.optInt("id")==0)mainScreen=item;else if(item.optInt("type")==2&&externalScreen==null)externalScreen=item;}
        if(DisplayModePolicy.isDocked(state.displayMode)&&externalScreen!=null&&mainScreen!=null){
            UiState.DisplayCanvas mirror=new UiState.DisplayCanvas();mirror.id=externalScreen.getInt("id");mirror.identity=externalScreen.getString("identity");mirror.label="TV mirror";mirror.mirroredPrimary=true;
            mirror.rotation=mainScreen.optInt("rotation");mirror.currentWidth=mainScreen.optInt("width");mirror.currentHeight=mainScreen.optInt("height");
            mirror.nativeWidth=externalScreen.optInt("nativeWidth");mirror.nativeHeight=externalScreen.optInt("nativeHeight");mirror.savedOptionId=profiles.dockedCanvas(profileKey,mirror.identity,mirror.nativeWidth,mirror.nativeHeight);
            for(DisplayModePolicy.Canvas choice:DisplayModePolicy.mirroredCanvases(mirror.nativeWidth,mirror.nativeHeight))mirror.options.add(new UiState.CanvasOption(choice.id,choice.label,choice.width,choice.height));
            state.displayCanvases.add(mirror);return;
        }
        for(int i=0;i<screens.length();i++){
            JSONObject item=screens.getJSONObject(i);UiState.DisplayCanvas canvas=new UiState.DisplayCanvas();
            canvas.id=item.getInt("id");canvas.identity=item.getString("identity");canvas.label=canvas.id==0?"Main screen":friendlyDevice(item);
            canvas.rotation=item.optInt("rotation");canvas.currentWidth=item.optInt("width");canvas.currentHeight=item.optInt("height");
            canvas.nativeWidth=item.optInt("nativeWidth");canvas.nativeHeight=item.optInt("nativeHeight");canvas.savedOptionId=profiles.canvas(profileKey,canvas.identity);
            JSONArray options=item.optJSONArray("canvasChoices");if(options!=null)for(int index=0;index<options.length();index++){
                JSONObject choice=options.getJSONObject(index);canvas.options.add(new UiState.CanvasOption(choice.getString("id"),choice.getString("label"),choice.getInt("width"),choice.getInt("height")));
            }
            state.displayCanvases.add(canvas);
        }
    }
    private String friendlyDevice(JSONObject display) {
        return OutputNamePolicy.suggestion(display.optString("product"),display.optString("name","External display"));
    }
    private String appName(String pkg) {
        try {return context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(pkg,0)).toString();}
        catch(Exception ignored) { return pkg.substring(pkg.lastIndexOf('.')+1); }
    }
    private void applyProfile() {
        lastEdit=SystemClock.uptimeMillis();
        applyMain();applyUpper();applyVolume();
        if(!state.volumeMuted)profiles.save(profileKey,state.main,state.upper,state.volume);
        applyAudio();
        int savedRefresh=profiles.refresh(profileKey);if(savedRefresh>0)try{request("setting",new JSONObject().put("id","refresh_mode").put("value",savedRefresh),false);}catch(Exception ignored){}
    }
    private void applyMain() {
        if(state.bridgeReady) {
            try {request("main",new JSONObject().put("percent",state.main),false);}catch(Exception ignored) { }
        } else {state.feedback="Reconnect system controls to adjust the main screen";render();}
    }
    private void applyMainPreview(){
        if(!state.bridgeReady)return;
        try{request("main_preview",new JSONObject().put("percent",state.main),false);}catch(Exception ignored){}
    }
    private void applyUpper() {
        if(!state.bridgeReady||!state.externalConnected)return;
        try {request("upper",target().put("percent",effectiveUpperDimPercent()),false);}catch(Exception ignored) { }
    }
    private int effectiveUpperDimPercent(){
        int saved=state.upperBlack?0:state.upper;
        return TvProfilePolicy.effectiveUpperDimPercent(panelOpen,panelTargetsExternal(),saved);
    }
    private void applyVolume() {
        if(audio.isStreamMute(AudioManager.STREAM_MUSIC)){state.volumeMuted=true;return;}
        int value=VolumeStepPolicy.indexForPercent(state.volume,streamVolumeMinimum(),streamVolumeMaximum());
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0);
        state.volume=currentVolume();
    }
    private void applyAudio(){if(!state.bridgeReady)return;try{request("audio",target().put("main",state.mainAudio).put("upper",state.upperAudio),false);}catch(Exception ignored){}}
    @Override public void setMainAudio(int percent){state.mainAudio=ControlLogic.percent(percent,false);if(state.mainAudioMuted&&state.mainAudio>0){state.mainAudioMuted=false;profiles.audioMuted(profileKey,false,false);}profiles.audio(profileKey,state.mainAudio,state.upperAudio);applyAudio();render();}
    @Override public void setUpperAudio(int percent){state.upperAudio=ControlLogic.percent(percent,false);if(state.upperAudioMuted&&state.upperAudio>0){state.upperAudioMuted=false;profiles.audioMuted(profileKey,true,false);}profiles.audio(profileKey,state.mainAudio,state.upperAudio);applyAudio();render();}
    @Override public void toggleAudioMute(String id){
        lastEdit=SystemClock.uptimeMillis();
        switch(id){
            case "volume": {
                boolean wasMuted=audio.isStreamMute(AudioManager.STREAM_MUSIC);
                if(!wasMuted){state.volume=currentVolume();save();}
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,wasMuted?AudioManager.ADJUST_UNMUTE:AudioManager.ADJUST_MUTE,0);
                state.volumeMuted=audio.isStreamMute(AudioManager.STREAM_MUSIC);
                if(wasMuted&&!state.volumeMuted){int restored=currentVolume();if(restored<=0&&state.volume>0){int raw=VolumeStepPolicy.indexForPercent(state.volume,streamVolumeMinimum(),streamVolumeMaximum());audio.setStreamVolume(AudioManager.STREAM_MUSIC,raw,0);restored=currentVolume();}state.volume=restored;save();}
                break;
            }
            case "audio_main":
                if(state.mainAudioMuted){state.mainAudio=profiles.audioRestore(profileKey,false);state.mainAudioMuted=false;profiles.audio(profileKey,state.mainAudio,state.upperAudio);profiles.audioMuted(profileKey,false,false);}
                else{profiles.audioMuted(profileKey,false,true);state.mainAudio=0;state.mainAudioMuted=true;profiles.audio(profileKey,state.mainAudio,state.upperAudio);}
                applyAudio();break;
            case "audio_upper":
                if(state.upperAudioMuted){state.upperAudio=profiles.audioRestore(profileKey,true);state.upperAudioMuted=false;profiles.audio(profileKey,state.mainAudio,state.upperAudio);profiles.audioMuted(profileKey,true,false);}
                else{profiles.audioMuted(profileKey,true,true);state.upperAudio=0;state.upperAudioMuted=true;profiles.audio(profileKey,state.mainAudio,state.upperAudio);}
                applyAudio();break;
            default:return;
        }
        render();
    }
    @Override public void setMetricMode(String id,String mode){
        if(!"cpu".equals(id)&&!"gpu".equals(id))return;
        String selected=MetricModePolicy.normalize(mode);
        android.content.SharedPreferences ui=context.getSharedPreferences("ui",Context.MODE_PRIVATE);
        if("cpu".equals(id)){state.cpuMetricMode=selected;ui.edit().putString(MetricModePolicy.CPU_PREF,selected).apply();}
        else{state.gpuMetricMode=selected;ui.edit().putString(MetricModePolicy.GPU_PREF,selected).apply();}
        render();
    }
    @Override public boolean restoreEditSnapshot(String key,DashboardEditSnapshot snapshot){
        if(snapshot==null||!key.equals(profileKey)||!key.equals(snapshot.profileKey))return false;
        state.controlOrder=ControlLogic.cleanOrder(snapshot.order);state.hiddenControls=ControlLogic.cleanHidden(snapshot.hidden);
        state.wideControls=ControlLogic.cleanHidden(snapshot.wide);state.smallControls=ControlLogic.cleanHidden(snapshot.small);
        state.controlSizes=ControlSizePolicy.normalizeSizes(snapshot.sizes);
        state.pinnedApps.clear();state.pinnedApps.addAll(snapshot.copyPinnedApps());
        state.cpuMetricMode=MetricModePolicy.normalize(snapshot.cpuMode);state.gpuMetricMode=MetricModePolicy.normalize(snapshot.gpuMode);
        profiles.restoreEditLayout(key,snapshot);
        context.getSharedPreferences("ui",Context.MODE_PRIVATE).edit()
            .putString(MetricModePolicy.CPU_PREF,state.cpuMetricMode).putString(MetricModePolicy.GPU_PREF,state.gpuMetricMode).apply();
        render();return true;
    }
    @Override public void openNativeSettings(String id){host.openControlSettings(id);}
    private void cycleFpsScreen(){state.fpsScreen=state.fpsScreen.equals("main")?"upper":"main";profiles.fpsScreen(profileKey,state.fpsScreen);render();}
    @Override public void control(String id){
        android.util.Log.i("NovaDeck","Control tapped: "+id);
        switch(id){case "clear_recents":if(!state.bridgeReady){host.openConnectionSettings();return;}request("clear_recents",null,true);return;case "swap":swap();return;case "up":moveUp();return;case "down":moveDown();return;case "refresh_toggle":toggleRefresh();return;case "fps_cycle":cycleFpsScreen();return;case "black_main":toggleScreenOff();return;case "black_upper":if(!state.externalConnected||!state.bridgeReady){host.message("Connect an upper display first");return;}state.upperBlack=!state.upperBlack;lastEdit=SystemClock.uptimeMillis();applyUpper();render();return;}
        if(Arrays.asList("wifi","bluetooth","rotation","dnd","charging","charge_limit","led","keep_awake","force_landscape","game_assistant","airplane","battery_saver","night","dark","inversion","location","screenrecord","controller_mouse").contains(id)){
            if(!state.bridgeReady){host.openConnectionSettings();return;}
            UiState.ControlStatus current=state.controls.get(id);
            if(current==null||!current.available){host.message("This toggle is unavailable on this firmware");return;}
            try{request("control",new JSONObject().put("id",id).put("enabled",!current.active),true);}catch(Exception ignored){}return;
        }
        host.performControl(id);
    }
    @Override public void setMain(int percent) {
        lastEdit=SystemClock.uptimeMillis();state.main=ControlLogic.percent(percent,true);save();render();
        if(mainChange!=null)main.removeCallbacks(mainChange);
        mainChange=this::applyMain;main.postDelayed(mainChange,70);
    }
    @Override public void previewMain(int percent){
        lastEdit=SystemClock.uptimeMillis();state.main=ControlLogic.percent(percent,true);save();render();
        if(mainChange!=null)main.removeCallbacks(mainChange);
        mainChange=this::applyMainPreview;main.postDelayed(mainChange,30);
    }
    @Override public void setUpper(int percent) {
        state.upperBlack=false;
        lastEdit=SystemClock.uptimeMillis();state.upper=ControlLogic.percent(percent,false);save();render();
        if(upperChange!=null)main.removeCallbacks(upperChange);
        upperChange=this::applyUpper;main.postDelayed(upperChange,70);
    }
    @Override public void setVolume(int percent) {
        lastEdit=SystemClock.uptimeMillis();state.volume=ControlLogic.percent(percent,false);
        if(state.volumeMuted){audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0);state.volumeMuted=audio.isStreamMute(AudioManager.STREAM_MUSIC);}
        applyVolume();save();render();
    }
    @Override public void setBoth(int percent){
        state.upperBlack=false;
        int value=ControlLogic.percent(percent,true);
        lastEdit=SystemClock.uptimeMillis();state.main=value;state.upper=value;save();render();
        if(mainChange!=null)main.removeCallbacks(mainChange);if(upperChange!=null)main.removeCallbacks(upperChange);
        mainChange=this::applyMain;upperChange=this::applyUpper;main.postDelayed(mainChange,70);main.postDelayed(upperChange,70);
    }
    @Override public void previewBoth(int percent){
        state.upperBlack=false;int value=ControlLogic.percent(percent,true);lastEdit=SystemClock.uptimeMillis();state.main=value;state.upper=value;save();render();
        if(mainChange!=null)main.removeCallbacks(mainChange);if(upperChange!=null)main.removeCallbacks(upperChange);
        mainChange=this::applyMainPreview;upperChange=this::applyUpper;main.postDelayed(mainChange,30);main.postDelayed(upperChange,70);
    }
    private void route(String command) {
        if(DisplayModePolicy.isDocked(state.displayMode)){host.message("App switching is available in dual-screen mode");return;}
        if(state.busy)return;
        if(!state.bridgeReady||externalId<0) {state.feedback="Connect an external display and system controls first";host.message(state.feedback);render();return;}
        try {request(command,target(),true);}catch(Exception ignored) { }
    }
    @Override public void swap(){route("swap");}
    @Override public void moveUp(){route("up");}
    @Override public void moveDown(){route("down");}
    @Override public void restore(){if(state.mainBlack)host.performControl("black_main");setMain(80);setUpper(100);}
    public void renameOutput(){if(!identity.isEmpty())promptName(true);}
    public String identity(){return identity;}
    public void renameOutput(String stableIdentity,String name){
        if(stableIdentity==null||stableIdentity.isEmpty()||name==null||name.trim().isEmpty())return;
        profiles.name(stableIdentity,name);if(identity.equals(stableIdentity)){state.profile=profiles.name(stableIdentity);render();}
    }
    public void refreshConnection(){if(running)poll();}
    private void promptName(boolean rename){
        String namedIdentity=identity;
        host.promptOutputName(namedIdentity,rename?state.profile:state.deviceName,rename,name->{
            if(name==null)return;
            profiles.name(namedIdentity,name);
            if(identity.equals(namedIdentity)){state.profile=profiles.name(identity);state.feedback="Settings save automatically";render();}
        });
    }
    @Override public void editControls(){host.showEditor();}
    public String layoutKey(){return profileKey;}
    public void systemUiShadeFocused(int displayId,java.util.function.Consumer<Boolean> callback){
        if(callback==null)return;
        if(!running||displayId<0){main.post(()->callback.accept(null));return;}
        try{
            io.execute(()->{
                Boolean focused=null;
                try{
                    JSONObject response=bridge.request("system_ui_shade",new JSONObject().put("display",displayId));
                    if(response.has("focusedShade")&&response.has("display")){
                        int reportedDisplay=response.getInt("display");
                        if(SystemUiShadePolicy.matchesDisplay(reportedDisplay,displayId))
                            focused=SystemUiShadePolicy.confirmedOnDisplay(response.getBoolean("focusedShade"),reportedDisplay,displayId);
                    }
                }catch(Exception failure){
                    if((context.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)
                        android.util.Log.w("NovaDeckShade","request failed="+failure.getClass().getSimpleName());
                }
                Boolean result=focused;
                main.post(()->callback.accept(running?result:null));
            });
        }catch(RejectedExecutionException ignored){main.post(()->callback.accept(null));}
    }
    public UiState.ResolutionPreview pendingResolution(){return pendingResolution;}
    public void previewResolution(String requestedProfile,int displayId,String displayIdentity,String optionId,java.util.function.Consumer<UiState.ResolutionPreview> callback){
        if(!running||!requestedProfile.equals(profileKey)||!state.bridgeReady){callback.accept(new UiState.ResolutionPreview(false,null,"Reconnect system controls first",optionId,displayIdentity,displayId,0,0));return;}
        boolean mirrored=state.displayCanvases.stream().anyMatch(canvas->canvas.mirroredPrimary&&canvas.id==displayId&&canvas.identity.equals(displayIdentity));
        if(mirrored&&!useExternalApps()){callback.accept(new UiState.ResolutionPreview(false,null,"Docked mode is no longer active",optionId,displayIdentity,displayId,0,0));return;}
        if(pendingResolution!=null){revertResolution(pendingResolution.transactionId,null);pendingResolution=null;}
        JSONObject args=canvasArgs(displayId,displayIdentity,optionId);
        String command=mirrored?"dock_canvas":"canvas_preview";
        if(mirrored)try{args.put("action","preview");}catch(JSONException ignored){}
        resolutionRequest(command,args,result->{
            if(!requestedProfile.equals(profileKey)){revertResolution(result.optString("transactionId"),null);callback.accept(new UiState.ResolutionPreview(false,null,"The active output changed",optionId,displayIdentity,displayId,0,0));return;}
            UiState.ResolutionPreview preview=new UiState.ResolutionPreview(true,result.optString("transactionId"),"",result.optString("optionId",optionId),result.optString("identity",displayIdentity),result.optInt("display",displayId),result.optInt("width"),result.optInt("height"));
            preview.mirroredPrimary=mirrored;
            pendingResolution=preview;callback.accept(preview);
        },error->callback.accept(new UiState.ResolutionPreview(false,null,error,optionId,displayIdentity,displayId,0,0)));
    }
    public void confirmResolution(String requestedProfile,String transactionId,java.util.function.Consumer<Boolean> callback){
        if(!requestedProfile.equals(profileKey)||pendingResolution==null||!pendingResolution.transactionId.equals(transactionId)){if(callback!=null)callback.accept(false);return;}
        UiState.ResolutionPreview preview=pendingResolution;
        JSONObject args=transactionArgs(transactionId);String command="canvas_keep";
        if(preview.mirroredPrimary){command="dock_canvas";try{args.put("action","keep").put("display",preview.displayId).put("identity",preview.displayIdentity);}catch(JSONException ignored){}}
        final String keepCommand=command;
        resolutionRequest(keepCommand,args,result->{
            if(!requestedProfile.equals(profileKey)){if(callback!=null)callback.accept(false);return;}
            if(preview.mirroredPrimary)profiles.dockedCanvas(requestedProfile,result.optString("optionId",preview.optionId));
            else profiles.canvas(requestedProfile,preview.displayIdentity,result.optString("optionId",preview.optionId));
            dockedCanvasApplySignature=preview.mirroredPrimary?dockedCanvasApplySignature:"";pendingResolution=null;poll();if(callback!=null)callback.accept(true);
        },error->{pendingResolution=null;poll();if(callback!=null)callback.accept(false);});
    }
    public void revertResolution(String transactionId,java.util.function.Consumer<Boolean> callback){
        if(transactionId==null||transactionId.isEmpty()){if(callback!=null)callback.accept(false);return;}
        boolean mirrored=pendingResolution!=null&&transactionId.equals(pendingResolution.transactionId)&&pendingResolution.mirroredPrimary;
        JSONObject args=transactionArgs(transactionId);String command="canvas_revert";
        if(mirrored){UiState.ResolutionPreview p=pendingResolution;command="dock_canvas";try{args.put("action","revert").put("display",p.displayId).put("identity",p.displayIdentity);}catch(JSONException ignored){}}
        resolutionRequest(command,args,result->{
            if(pendingResolution!=null&&transactionId.equals(pendingResolution.transactionId))pendingResolution=null;poll();if(callback!=null)callback.accept(true);
        },error->{if(pendingResolution!=null&&transactionId.equals(pendingResolution.transactionId))pendingResolution=null;poll();if(callback!=null)callback.accept(false);});
    }
    private void resolutionRequest(String command,JSONObject args,java.util.function.Consumer<JSONObject> success,java.util.function.Consumer<String> failure){
        io.execute(()->{try{JSONObject result=bridge.request(command,args);main.post(()->{if(running)success.accept(result);});}catch(Exception error){main.post(()->{if(running)failure.accept(error.getMessage()==null?"Logical resolution unavailable":error.getMessage());});}});
    }
    private static JSONObject canvasArgs(int id,String identity,String option){try{return new JSONObject().put("display",id).put("identity",identity).put("optionId",option);}catch(JSONException ignored){return new JSONObject();}}
    private static JSONObject transactionArgs(String id){try{return new JSONObject().put("transactionId",id);}catch(JSONException ignored){return new JSONObject();}}
    public int externalDisplay(){return externalId;}
    public boolean dockedMode(){return DisplayModePolicy.isDocked(state.displayMode);}
    public int dashboardDisplayId(){return state.showDuoDashOnExternal&&state.externalConnected?externalId:0;}
    public int panelWindowDisplayId(){return DisplayModePolicy.panelWindowDisplay(dockedMode(),state.showDuoDashOnExternal,state.externalConnected,externalId);}
    public boolean panelTargetsExternal(){return DisplayModePolicy.panelTargetsExternal(state.showDuoDashOnExternal,state.externalConnected);}
    public int panelDensityDisplayId(){return panelWindowDisplayId();}
    public boolean includePanelInMirror(){return !DisplayModePolicy.excludePanelFromMirroring(dockedMode(),state.showDuoDashOnExternal,state.externalConnected);}
    public boolean useExternalApps(){return DisplayModePolicy.isDocked(state.displayMode);}
    public void displayMode(String expectedProfileKey,String mode){
        if(expectedProfileKey==null||!expectedProfileKey.equals(profileKey)||!state.externalConnected)return;
        String normalized=DisplayModePolicy.normalizeMode(mode);if(normalized.equals(state.displayMode))return;
        String previousMode=state.displayMode;
        state.displayMode=normalized;state.useExternalApps=DisplayModePolicy.isDocked(normalized);profiles.displayMode(profileKey,normalized);
        canvasApplySignature="";
        if(DisplayModePolicy.isDocked(previousMode)&&!state.useExternalApps)try{request("dock_canvas",target().put("action","release"),false);}catch(Exception ignored){}
        poll();host.displayTargetChanged();render();
    }
    public void showDuoDashOn(String expectedProfileKey,String display){
        if(expectedProfileKey==null||!expectedProfileKey.equals(profileKey))return;
        int previous=dashboardDisplayId();boolean previousExternal=panelTargetsExternal();profiles.showDuoDashOn(profileKey,display);
        state.showDuoDashOnExternal="external".equals(display)&&state.externalConnected;
        if(previous!=dashboardDisplayId()||previousExternal!=panelTargetsExternal())host.displayTargetChanged();render();
    }
    public void saveDockedCanvas(String expectedProfileKey,String displayIdentity,String optionId){
        if(expectedProfileKey==null||!expectedProfileKey.equals(profileKey)||!identity.equals(displayIdentity)||!useExternalApps())return;
        profiles.dockedCanvas(profileKey,optionId);dockedCanvasApplySignature="";poll();
    }
    public void toggleScreenOff(){
        if(!state.bridgeReady||!state.externalConnected){host.message("Connect an external screen first");return;}
        if(!state.panelPowerSupported){host.performControl("black_main");return;}
        state.screenOffRequested=!state.screenOffRequested;profiles.screenOffRequested(profileKey,state.screenOffRequested);
        if(state.screenOffRequested){temporaryWakePackage="";temporaryWakeSeen=false;temporaryWakeDeadline=0;}
        if(state.screenOffRequested)host.closePanel();else requestPanelPower("wake",false);
        render();
    }
    private void syncPanelPower(){
        if(!running||stopping||!state.panelPowerSupported)return;
        boolean temporaryWake=false;
        if(!temporaryWakePackage.isEmpty()){
            boolean found=!temporaryWakePackage.equals("*")&&lastTasks.stream().anyMatch(task->task.display==0&&temporaryWakePackage.equals(task.pkg));
            if(found)temporaryWakeSeen=true;
            if(temporaryWakeSeen&&!found||SystemClock.uptimeMillis()>temporaryWakeDeadline){temporaryWakePackage="";temporaryWakeSeen=false;temporaryWakeDeadline=0;}
            else temporaryWake=true;
        }
        boolean identityMatches=!identity.isEmpty()&&profileKey.equals(ControlLogic.outputKey(identity));
        boolean shouldOff=TvProfilePolicy.shouldTurnPanelOff(state.screenOffRequested,state.bridgeReady&&state.externalConnected,identityMatches,state.panelPowerSupported,panelOpen&&!panelTargetsExternal(),temporaryWake);
        if(shouldOff!=panelPowerApplied&&(!panelPowerRequestPending||pendingPanelPowerOff!=shouldOff))requestPanelPower(shouldOff?"off":"wake",shouldOff,false);
    }
    private void requestPanelPower(String action,boolean off){
        requestPanelPower(action,off,false);
    }
    private void requestPanelPower(String action,boolean off,boolean force){
        if(!state.bridgeReady||externalId<=0||identity.isEmpty())return;
        if(panelPowerRequestPending&&pendingPanelPowerOff==off)return;
        if(!force&&!panelPowerRequestPending&&panelPowerApplied==off&&!action.equals("release"))return;
        final int display=externalId;final String requestedIdentity=identity;final long sequence=++panelPowerSequence;
        panelPowerRequestPending=true;pendingPanelPowerOff=off;
        try{
            JSONObject args=target().put("action",action).put("desiredOff",state.screenOffRequested);
            io.execute(()->{
                try{
                    bridge.request("panel_power",args);
                    main.post(()->{if(sequence!=panelPowerSequence||display!=externalId||!requestedIdentity.equals(identity))return;panelPowerRequestPending=false;if(running)panelPowerApplied=off;});
                }catch(Exception failure){main.post(()->{
                    if(sequence!=panelPowerSequence)return;panelPowerRequestPending=false;
                    if(off&&failure.getMessage()!=null&&failure.getMessage().contains("already enabled by Android")){
                        state.screenOffRequested=false;profiles.screenOffRequested(profileKey,false);host.message("Android already controls the handheld screen");render();
                    }else if(off)android.util.Log.w("NovaDeck","Could not apply handheld screen mode");
                });}
            });
        }catch(JSONException ignored){}
    }
    public void prepareForStop(){if(stopping)return;stopping=true;if(state.bridgeReady&&externalId>0)requestPanelPower("release",false);}
    public void wakeHandheldForActivity(String packageName){
        temporaryWakePackage=packageName==null||packageName.isEmpty()?"*":packageName;temporaryWakeSeen=false;temporaryWakeDeadline=SystemClock.uptimeMillis()+120000;
        requestPanelPower("wake",false,true);
    }
    public void configureShortcuts(){
        if(!state.bridgeReady)return;String keys=context.getSharedPreferences("ui",0).getString("shortcuts","");
        if(keys.equals(configuredShortcuts))return;configuredShortcuts=keys;
        try{request("shortcuts",new JSONObject().put("keys",keys),false);}catch(Exception ignored){}
    }
    public void recordGamepad(boolean enabled){if(!state.bridgeReady)return;try{request("recording",new JSONObject().put("enabled",enabled),false);}catch(Exception ignored){}}
    public void nativeMode(String id,int value){if(!state.bridgeReady){host.message("Reconnect system controls first");return;}try{request("setting",new JSONObject().put("id",id).put("value",value),true);}catch(Exception ignored){}}
    public void toggleRefresh(){
        if(!state.bridgeReady){host.message("Reconnect system controls to change refresh rate");return;}
        UiState.ControlStatus control=state.controls.get("refresh_mode");
        if(control==null||!control.available||control.optionValues.isEmpty()){host.message("Supported display modes are still loading");return;}
        int selected=control.optionValues.contains(control.value)?control.value:Math.round(state.refreshHz);
        int next=NativeModes.nextRefresh(selected,control.optionValues.stream().mapToInt(Integer::intValue).toArray());
        if(next>0)nativeMode("refresh_mode",next);
    }
    public void stopScreenRecording(){if(!state.bridgeReady)return;try{request("control",new JSONObject().put("id","screenrecord").put("enabled",false),true);}catch(Exception ignored){}}
    public void wide(String key,String ids){if(!key.equals(profileKey))return;state.wideControls=ControlLogic.cleanHidden(ids);profiles.wide(key,state.wideControls);render();}
    @Override public boolean resizeControlSize(String key,String id,boolean grow){
        if(!key.equals(profileKey)||!ControlLogic.action(id))return false;
        ControlSizePolicy.Sizes sizes=ControlSizePolicy.resize(id,state.smallControls,state.wideControls,state.controlSizes,state.actionColumns,grow);
        if(!sizes.changed)return false;
        state.smallControls=sizes.small;state.wideControls=sizes.wide;state.controlSizes=sizes.explicit;
        profiles.actionSizes(key,sizes.small,sizes.wide,sizes.explicit);render();return true;
    }
    @Override public void setControlWide(String key,String id,boolean wide){
        if(!key.equals(profileKey)||!ControlLogic.action(id))return;
        LinkedHashSet<String> ids=new LinkedHashSet<>(Arrays.asList(ControlLogic.cleanHidden(state.wideControls).split(",")));
        if(wide)ids.add(id);else ids.remove(id);
        LinkedHashSet<String> small=new LinkedHashSet<>(Arrays.asList(ControlLogic.cleanHidden(state.smallControls).split(",")));small.remove(id);
        LinkedHashMap<String,Integer> explicit=new LinkedHashMap<>();for(String item:ControlSizePolicy.normalizeSizes(state.controlSizes).split(",")){int separator=item.lastIndexOf(':');if(separator>0)try{explicit.put(item.substring(0,separator),Integer.parseInt(item.substring(separator+1)));}catch(NumberFormatException ignored){}}
        explicit.remove(id);List<String> explicitItems=new ArrayList<>();for(Map.Entry<String,Integer> entry:explicit.entrySet())explicitItems.add(entry.getKey()+":"+entry.getValue());
        state.smallControls=String.join(",",small);state.wideControls=String.join(",",ids);state.controlSizes=String.join(",",explicitItems);
        profiles.actionSizes(key,state.smallControls,state.wideControls,state.controlSizes);render();
    }
    @Override public void removeControl(String key,String id){
        if(!key.equals(profileKey)||!ControlCatalog.known(id))return;
        if(ControlCatalog.isApp(id)){removePinnedApp(key,id);return;}
        LinkedHashSet<String> hidden=new LinkedHashSet<>(Arrays.asList(ControlLogic.cleanHidden(state.hiddenControls).split(",")));hidden.add(id);
        updateLayout(key,state.controlOrder,String.join(",",hidden));
    }
    private void removePinnedApp(String key,String id){
        List<String> appIds=new ArrayList<>();for(UiState.AppShortcut app:state.pinnedApps)appIds.add(app.id);
        ControlLogic.AppControlState cleaned=ControlLogic.cleanAppControls(state.controlOrder,state.hiddenControls,state.wideControls,appIds,id);
        Set<String> keep=new LinkedHashSet<>(cleaned.pinnedAppIds);state.pinnedApps.removeIf(app->!keep.remove(app.id));
        state.controlOrder=cleaned.order;state.hiddenControls=cleaned.hidden;state.wideControls=cleaned.wide;
        List<String> retainedIds=new ArrayList<>();for(UiState.AppShortcut value:state.pinnedApps)retainedIds.add(value.id);
        state.smallControls=ControlSizePolicy.retainSmall(state.smallControls,retainedIds);state.controlSizes=ControlSizePolicy.retainSizes(state.controlSizes,retainedIds);profiles.actionSizes(key,state.smallControls,state.wideControls,state.controlSizes);
        profiles.replaceLayout(key,state.controlOrder,state.hiddenControls,state.wideControls,state.pinnedApps);render();
    }
    public void pin(String key,UiState.AppShortcut app){
        if(!key.equals(profileKey))return;
        state.pinnedApps.removeIf(old->old.id.equals(app.id));state.pinnedApps.add(app);profiles.pinned(key,state.pinnedApps);
        updateLayout(key,state.controlOrder+","+app.id,state.hiddenControls);
        if(Arrays.asList(state.hiddenControls.split(",")).contains(app.id))host.message("App saved. Select it in Edit controls to show it.");
    }
    public void unpin(String key,String id){if(!key.equals(profileKey))return;removePinnedApp(key,id);}
    public void style(String key,boolean horizontal,boolean compact){
        if(!key.equals(profileKey))return;
        state.horizontalSliders=horizontal;state.compactControls=compact;profiles.style(key,horizontal,compact);render();
    }
    public void actionColumns(String key,int columns){
        if(!key.equals(profileKey))return;
        state.actionColumns=ControlLogic.actionColumns(columns);profiles.actionColumns(key,state.actionColumns);render();
    }
    public void showBatteryTime(boolean show){
        state.showBatteryTime=show;context.getSharedPreferences("ui",Context.MODE_PRIVATE).edit().putBoolean("showBatteryTime",show).apply();render();
    }
    private void setWifiConnected(boolean connected){main.post(()->{if(!running||state.wifiConnected==connected)return;state.wifiConnected=connected;render();if(connected)host.networkAvailable();else host.networkUnavailable();});}
    public void showHeaderHz(boolean show){showHeaderPreference("showHeaderHz",show);state.showHeaderHz=show;render();}
    public void showHeaderWifi(boolean show){showHeaderPreference("showHeaderWifi",show);state.showHeaderWifi=show;render();}
    public void showHeaderClock(boolean show){showHeaderPreference("showHeaderClock",show);state.showHeaderClock=show;render();}
    private void showHeaderPreference(String key,boolean show){context.getSharedPreferences("ui",Context.MODE_PRIVATE).edit().putBoolean(key,show).apply();}
    public void updateLayout(String key,String order,String hidden){
        if(!key.equals(profileKey))return;
        state.controlOrder=ControlLogic.cleanOrder(order);state.hiddenControls=ControlLogic.cleanHidden(hidden);
        profiles.layout(profileKey,state.controlOrder,state.hiddenControls);render();
    }
    @Override public void moveControl(String key,String from,String to){
        if(!key.equals(profileKey)||from==null||to==null||from.equals(to))return;
        updateLayout(key,ControlLogic.moveOrder(state.controlOrder,from,to),state.hiddenControls);
    }
    @Override public void replaceControl(String key,String oldId,String newId){replaceControl(key,oldId,newId,null);}
    @Override public void replaceControlWithApp(String key,String oldId,UiState.AppShortcut app){replaceControl(key,oldId,app.id,app);}
    private void replaceControl(String key,String oldId,String newId,UiState.AppShortcut app){
        if(!key.equals(profileKey)||oldId==null||newId==null||oldId.equals(newId))return;
        String[] layout=ControlLogic.replaceLayout(state.controlOrder,state.hiddenControls,oldId,newId);
        if(layout==null){host.message("Choose an inactive control from the same section");return;}
        List<UiState.AppShortcut> apps=new ArrayList<>(state.pinnedApps);
        if(ControlCatalog.isApp(oldId))apps.removeIf(value->value.id.equals(oldId));
        if(app!=null){apps.removeIf(value->value.id.equals(app.id));apps.add(app);}
        LinkedHashSet<String> wide=new LinkedHashSet<>(Arrays.asList(ControlLogic.cleanHidden(state.wideControls).split(",")));wide.remove(oldId);state.wideControls=String.join(",",wide);
        LinkedHashSet<String> small=new LinkedHashSet<>(Arrays.asList(ControlLogic.cleanHidden(state.smallControls).split(",")));small.remove(oldId);state.smallControls=String.join(",",small);
        state.controlSizes=ControlSizePolicy.removeSize(oldId,state.controlSizes);
        state.pinnedApps.clear();state.pinnedApps.addAll(apps);
        state.controlOrder=ControlLogic.cleanOrder(layout[0]);state.hiddenControls=ControlLogic.cleanHidden(layout[1]);
        if(ControlCatalog.isApp(oldId)){
            List<String> appIds=new ArrayList<>();for(UiState.AppShortcut value:state.pinnedApps)appIds.add(value.id);
            ControlLogic.AppControlState cleaned=ControlLogic.cleanAppControls(state.controlOrder,state.hiddenControls,state.wideControls,appIds,oldId);
            state.controlOrder=cleaned.order;state.hiddenControls=cleaned.hidden;state.wideControls=cleaned.wide;
            state.controlSizes=ControlSizePolicy.retainSizes(state.controlSizes,cleaned.pinnedAppIds);
        }
        List<String> retainedIds=new ArrayList<>();for(UiState.AppShortcut value:state.pinnedApps)retainedIds.add(value.id);
        state.smallControls=ControlSizePolicy.retainSmall(state.smallControls,retainedIds);state.controlSizes=ControlSizePolicy.retainSizes(state.controlSizes,retainedIds);profiles.actionSizes(key,state.smallControls,state.wideControls,state.controlSizes);
        profiles.replaceLayout(key,state.controlOrder,state.hiddenControls,state.wideControls,state.pinnedApps);render();
    }
    @Override public void close(){host.closePanel();}
    @Override public void setup(){host.showSetup();}
    @Override public void connection(){host.openConnectionSettings();}
    @Override public void editControl(String id){host.editControl(id);}
    @Override public void onDisplayAdded(int id){main.postDelayed(this::poll,350);}
    @Override public void onDisplayRemoved(int id){if(id==externalId)offeredNames.remove(identity);main.postDelayed(this::poll,100);}
    @Override public void onDisplayChanged(int id){main.postDelayed(this::poll,250);}
    public void stop() {
        prepareForStop();
        if(DeckAccessibilityService.connected!=null)DeckAccessibilityService.connected.nativeGamepad=false;
        running=false;main.removeCallbacksAndMessages(null);displays.unregisterDisplayListener(this);
        if(connectivity!=null)try{connectivity.unregisterNetworkCallback(wifiCallback);}catch(Exception ignored){}
        context.sendBroadcast(new Intent(MirrorActivity.CLOSE).setPackage(context.getPackageName()));
        io.execute(()->{try{bridge.request("reset",null);}catch(Exception ignored){}});io.shutdown();
    }
    private void sample() {
        if(SystemClock.uptimeMillis()-lastSample<1000)return;
        lastSample=SystemClock.uptimeMillis();
        Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(battery!=null) {
            state.battery=Math.round(100f*battery.getIntExtra(BatteryManager.EXTRA_LEVEL,0)/Math.max(1,battery.getIntExtra(BatteryManager.EXTRA_SCALE,100)));
            state.temperatureC=Float.isFinite(state.cpuTemperatureC)?state.cpuTemperatureC:battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)/10f;
            state.temperatureLabel=Float.isFinite(state.cpuTemperatureC)?"CPU temp":"Battery temp";
            BatteryManager manager=(BatteryManager)context.getSystemService(Context.BATTERY_SERVICE);
            int status=battery.getIntExtra(BatteryManager.EXTRA_STATUS,BatteryManager.BATTERY_STATUS_UNKNOWN);
            state.batteryPowered=battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
            state.batteryCharging=status==BatteryManager.BATTERY_STATUS_CHARGING;
            state.batteryFull=status==BatteryManager.BATTERY_STATUS_FULL;
            long chargeCounter=manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            int averageCurrent=BatteryRuntimeEstimator.chooseDischargeCurrent(
                manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
                manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
            state.batteryRuntimeMinutes=batteryRuntime.update(status==BatteryManager.BATTERY_STATUS_DISCHARGING,state.batteryPowered||state.batteryCharging||state.batteryFull,chargeCounter,averageCurrent,SystemClock.elapsedRealtime());
            int current=manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            int voltage=battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE,0);
            state.powerW=current==Integer.MIN_VALUE||voltage==0?Float.NaN:Math.abs(current/1000000f*voltage/1000f);
        }
        ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();
        ((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
        state.totalMemoryGb=memory.totalMem/1073741824f;
        state.usedMemoryGb=(memory.totalMem-memory.availMem)/1073741824f;
    }
}
