package dev.nova.deck.bridge;

import android.content.ComponentName;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import dev.nova.deck.BridgeClient;
import dev.nova.deck.ControlLogic;
import dev.nova.deck.DisplayModePolicy;
import dev.nova.deck.TvProfilePolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Strict local control service, launched as ADB shell. No generic command execution endpoint. */
public final class BridgeMain {
    private static final String SWAP_STRATEGY = BridgeClient.EXPECTED_SWAP_STRATEGY;
    private static final int BRIDGE_BUILD = BridgeClient.EXPECTED_BRIDGE_BUILD;
    private final int appUid;
    private final byte[] secret;
    private final Class<?> displayClass, taskClass, surfaceClass, transactionClass;
    private final Object displays, tasks, windowManager;
    private final ShellAudio audio=new ShellAudio();
    private final ShellFps fps=new ShellFps();
    private final ShellDevice device=new ShellDevice();
    private final DockedCanvasLease dockedCanvasLease=new DockedCanvasLease(device::run);
    private final PanelFocusLease panelFocusLease=new PanelFocusLease();
    private GamepadMonitor gamepad;
    private final Map<Integer,DimLayer> layers=new HashMap<>();
    private final Map<String,Integer> compositionStacks=new HashMap<>();
    private final Map<Integer,Integer> movedTaskDestinations=new HashMap<>();
    private long compositionReadAt;
    private volatile long lastContact=android.os.SystemClock.elapsedRealtime();
    private boolean temporaryMainBrightness;
    private CanvasPreview canvasPreview;
    private DockedCanvasPreview dockedCanvasPreview;
    private String dockedCanvasIdentity,dockedCanvasOption;
    private int dockedCanvasDisplay=-1,dockedCanvasWidth,dockedCanvasHeight;
    private long dockedCanvasNextRenewal;
    private boolean panelPowerLease;
    private boolean panelPowerDesiredOff;
    private String panelPowerIdentity="",panelPowerBaseline,panelPowerLastWritten;
    private long panelPowerNextRenewal;
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();

    public static void main(String[] args) throws Exception {
        if(args.length!=2||args[1].length()!=64) throw new IllegalArgumentException("UID and session token required");
        BridgeMain service=new BridgeMain(Integer.parseInt(args[0]),args[1]);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{service.releasePanelFocusQuietly();service.clearLayers(true);}));
        service.watchdog.scheduleAtFixedRate(service::tick,1,1,TimeUnit.SECONDS);
        try(LocalServerSocket server=new LocalServerSocket(BridgeClient.SOCKET)) {
            System.out.println("DuoDash system controls ready");
            while(true) {
                try(LocalSocket socket=server.accept()) {
                    socket.setSoTimeout(3000);
                    if(socket.getPeerCredentials().getUid()!=service.appUid) continue;
                    JSONObject reply;
                    try {
                        JSONObject request=new JSONObject(BridgeClient.readLine(socket.getInputStream(),16384));
                        byte[] supplied=request.optString("token").getBytes(StandardCharsets.UTF_8);
                        if(!MessageDigest.isEqual(service.secret,supplied)) throw new SecurityException("Session not authorized");
                        service.lastContact=android.os.SystemClock.elapsedRealtime();
                        reply=service.handle(request).put("ok",true);
                    } catch(Exception failure) {
                        Throwable cause=failure instanceof InvocationTargetException ? ((InvocationTargetException)failure).getCause():failure;
                        reply=new JSONObject().put("ok",false).put("error",cause.getMessage()==null?cause.getClass().getSimpleName():cause.getMessage());
                    }
                    socket.getOutputStream().write((reply+"\n").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch(IOException disconnected) { /* A timed-out or closed client must not stop controls. */ }
            }
        } finally { service.clearLayers(true); service.watchdog.shutdownNow(); }
    }

    private synchronized void tick(){
        if(android.os.SystemClock.elapsedRealtime()-lastContact>30000){clearLayers(false);return;}
        expireCanvasPreview();
        expireDockedCanvasPreview();
        if(dockedCanvasLease.active()){
            String leasedIdentity=dockedCanvasLease.identity();
            if(!externalIdentityPresent(leasedIdentity))try{releaseDockedCanvas();}catch(Exception failure){android.util.Log.w("NovaDeckBridge","Disconnected docked canvas restore failed");}
            else if(android.os.SystemClock.elapsedRealtime()>=dockedCanvasNextRenewal)try{
                dockedCanvasLease.renew();dockedCanvasNextRenewal=android.os.SystemClock.elapsedRealtime()+10000;
            }catch(Exception failure){android.util.Log.w("NovaDeckBridge","Docked canvas lease renewal failed");}
        }
        if(panelPowerLease&&!externalIdentityPresent(panelPowerIdentity))restorePanelPowerLease(true);
        else if(panelPowerLease)try{
            if(!TvProfilePolicy.stillOwnsSetting(device.closeScreenWhenOutputVideo(),panelPowerLastWritten))restorePanelPowerLease(false);
            else if(android.os.SystemClock.elapsedRealtime()>=panelPowerNextRenewal){device.armPanelPowerFailsafe();panelPowerNextRenewal=android.os.SystemClock.elapsedRealtime()+10000;}
        }catch(Exception failure){restorePanelPowerLease(false);}
        // New media players inherit saved gains even while the dashboard is closed.
        if(audio.needsRefresh())try{int external=-1;for(int id:(int[])displayClass.getMethod("getDisplayIds").invoke(displays)){Object screen=info(id);if(id!=0&&screen!=null&&integer(screen,"type")==2){external=id;break;}}audio.status(taskList(),external);}catch(Exception failure){android.util.Log.w("NovaDeckBridge","App-audio refresh unavailable");}
    }

    private BridgeMain(int uid,String token) throws Exception {
        if(uid<10000) throw new IllegalArgumentException("Application UID required");
        appUid=uid; secret=token.getBytes(StandardCharsets.UTF_8);
        gamepad=new GamepadMonitor(device,token);
        Runtime.getRuntime().addShutdownHook(new Thread(gamepad::close));
        displayClass=Class.forName("android.hardware.display.DisplayManagerGlobal");
        displays=displayClass.getMethod("getInstance").invoke(null);
        taskClass=Class.forName("android.app.IActivityTaskManager");
        tasks=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        windowManager=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
        surfaceClass=Class.forName("android.view.SurfaceControl");
        transactionClass=Class.forName("android.view.SurfaceControl$Transaction");
    }

    private synchronized JSONObject handle(JSONObject request) throws Exception {
        String command=request.getString("command");
        switch(command) {
            case "ping": return new JSONObject().put("bridgeBuild",BRIDGE_BUILD).put("swapStrategy",SWAP_STRATEGY);
            case "status": return status();
            case "panel_focus": return panelFocus(request);
            case "system_ui_shade": return systemUiShade(request);
            case "dock_canvas": return dockedCanvas(request);
            case "canvas_preview": return previewCanvas(request);
            case "canvas_keep": return keepCanvas(request);
            case "canvas_revert": return revertCanvas(request);
            case "canvas_apply": return applySavedCanvas(request);
            case "main": {
                int value=percentage(request,true);
                displayClass.getMethod("setTemporaryBrightness",int.class,float.class).invoke(displays,0,value/100f);
                displayClass.getMethod("setBrightness",int.class,float.class).invoke(displays,0,value/100f);
                temporaryMainBrightness=false;
                return new JSONObject().put("main",value);
            }
            case "main_preview": {
                int value=percentage(request,true);
                displayClass.getMethod("setTemporaryBrightness",int.class,float.class).invoke(displays,0,value/100f);
                temporaryMainBrightness=true;
                return new JSONObject().put("mainPreview",value);
            }
            case "main_preview_clear": {
                displayClass.getMethod("setTemporaryBrightness",int.class,float.class).invoke(displays,0,Float.NaN);
                temporaryMainBrightness=false;
                return new JSONObject();
            }
            case "upper": {
                Object info=external(request);
                int value=percentage(request,false);
                setDim(info,value);
                return new JSONObject().put("upper",value);
            }
            case "audio":{
                int id=request.optInt("display",-1);if(id>0)external(request);
                int main=percentage(new JSONObject().put("percent",request.get("main")),false),upper=percentage(new JSONObject().put("percent",request.get("upper")),false);
                audio.gains(main,upper);return audio.status(taskList(),id);
            }
            case "control":device.control(request.getString("id"),request.getBoolean("enabled"));return new JSONObject().put("message","Control updated");
            case "clear_recents":return clearRecentTasks();
            case "setting":device.mode(request.getString("id"),request.getInt("value"));return new JSONObject().put("message","Native setting updated");
            case "panel_power":return panelPower(request);
            case "shortcuts":gamepad.configure(dev.nova.deck.ShortcutMatcher.normalize(request.optString("keys")),dev.nova.deck.ShortcutBindings.normalizeControls(request.optString("controls")));return new JSONObject();
            case "recording":gamepad.record(request.getBoolean("enabled"));return new JSONObject();
            case "swap": case "up": case "down": {
                Object info=external(request);
                int externalId=integer(info,"displayId");
                List<ControlLogic.Task> current=taskList();
                List<ControlLogic.Move> moves;
                if(command.equals("swap")) moves=ControlLogic.swapPlan(current,externalId);
                else {
                    int from=command.equals("up")?0:externalId;
                    ControlLogic.Task task=ControlLogic.foreground(current,from);
                    moves=task==null?Collections.emptyList():List.of(new ControlLogic.Move(task.id,from,from==0?externalId:0));
                }
                if(moves.isEmpty()) throw new IllegalStateException("Open an app on a screen first");
                if(command.equals("swap")){
                    StringBuilder plan=new StringBuilder();
                    for(ControlLogic.Move move:moves){if(plan.length()>0)plan.append(',');plan.append(move.task).append(':').append(move.from).append('>').append(move.to);}
                    android.util.Log.i("DuoDashSwap","begin externalDisplay="+externalId+" rootTaskMoves="+plan);
                }
                List<ControlLogic.Move> completed=new ArrayList<>();
                try {
                    CoordinatedTaskMoves.execute(moves,move -> {
                        taskClass.getMethod("moveRootTaskToDisplay",int.class,int.class).invoke(tasks,move.task,move.to);
                    });
                    List<ControlLogic.Task> afterMoves=taskList();
                    for(ControlLogic.Move move:moves) {
                        boolean moved=false;for(ControlLogic.Task actual:afterMoves)if(actual.id==move.task&&actual.display==move.to)moved=true;
                        if(!moved)throw new IllegalStateException("Android kept this app on its original screen");
                        completed.add(move);
                        movedTaskDestinations.put(move.task,move.to);
                        if(command.equals("swap"))android.util.Log.i("DuoDashSwap","confirmed rootTask="+move.task+" display="+move.to);
                    }
                } catch(Exception error) {
                    if(command.equals("swap")){Throwable cause=error.getCause();android.util.Log.e("DuoDashSwap","failed rootTask move cause="+(cause==null?error.getClass().getSimpleName():cause.getClass().getSimpleName()));}
                    // A failed task-manager call can still have moved its task. Wait for all
                    // parallel calls above, then roll back only tasks confirmed at the target.
                    List<ControlLogic.Task> actualNow=taskList();
                    completed.clear();
                    for(ControlLogic.Move move:moves)for(ControlLogic.Task actual:actualNow)
                        if(actual.id==move.task&&actual.display==move.to){completed.add(move);break;}
                    Collections.reverse(completed);
                    for(ControlLogic.Move move:completed) try {
                        taskClass.getMethod("moveRootTaskToDisplay",int.class,int.class).invoke(tasks,move.task,move.from);
                    } catch(Exception rollback) { System.err.println("Task rollback failed: "+rollback.getClass().getSimpleName()); }
                    throw new IllegalStateException("Could not move this app; Android may restrict its display");
                }
                if(command.equals("swap")&&moves.size()==2){
                    Thread.sleep(300L);
                    List<ControlLogic.Task> settled=taskList();
                    for(ControlLogic.Move move:moves){
                        ControlLogic.Task top=ControlLogic.foreground(settled,move.to);
                        if(top!=null&&top.id!=move.task&&settled.stream().anyMatch(task->task.id==move.task&&task.display==move.to)){
                            try{
                                taskClass.getMethod("moveTaskToFront",int.class,int.class,android.os.Bundle.class)
                                    .invoke(tasks,move.task,0,new android.os.Bundle());
                                android.util.Log.i("DuoDashSwap","restored moved rootTask="+move.task+" to display="+move.to);
                            }catch(Exception unavailable){
                                // Older firmware can omit the hidden focus helper; the move itself succeeded.
                            }
                        }
                    }
                    android.util.Log.i("DuoDashSwap","settled strategy="+SWAP_STRATEGY);
                }
                if(command.equals("swap"))android.util.Log.i("DuoDashSwap","complete externalDisplay="+externalId);
                return new JSONObject().put("message",command.equals("swap")?"Screens switched":"App moved");
            }
            case "reset": releasePanelFocusQuietly();clearLayers(false); return new JSONObject();
            default: throw new IllegalArgumentException("Unknown control");
        }
    }

    private JSONObject panelFocus(JSONObject request)throws Exception{
        if(request.getBoolean("enabled")){
            int display=request.getInt("display");
            if(display<0||info(display)==null)throw new IllegalArgumentException("Controller display is unavailable");
            int target=PanelFocusLease.targetTask(taskList(),display);
            if(target<0)throw new IllegalStateException("No visible task is available on this display");
            int current=focusedTaskId();
            int toFocus=panelFocusLease.acquire(current,target,display);
            if(toFocus>=0){
                try{focusTask(toFocus);}
                catch(Exception failure){panelFocusLease.clear();throw failure;}
            }
            Object focused=taskClass.getMethod("getFocusedRootTaskInfo").invoke(tasks);
            int now=focused==null?-1:integer(focused,"taskId");
            return new JSONObject().put("enabled",true).put("display",display)
                .put("focused",now==target).put("leased",panelFocusLease.active());
        }
        int restore=panelFocusLease.release(focusedTaskId());
        boolean restored=false;
        if(restore>=0&&taskExists(restore)){
            focusTask(restore);
            restored=focusedTaskId()==restore;
        }
        Object focused=taskClass.getMethod("getFocusedRootTaskInfo").invoke(tasks);
        return new JSONObject().put("enabled",false).put("restored",restored)
            .put("focusedTask",focused==null?-1:integer(focused,"taskId"));
    }

    private int focusedTaskId()throws Exception{
        Object focused=taskClass.getMethod("getFocusedRootTaskInfo").invoke(tasks);
        return focused==null?-1:integer(focused,"taskId");
    }

    private void releasePanelFocus()throws Exception{
        int restore=panelFocusLease.release(focusedTaskId());
        if(restore>=0&&taskExists(restore))focusTask(restore);
    }

    private void releasePanelFocusQuietly(){try{releasePanelFocus();}catch(Exception ignored){panelFocusLease.clear();}}

    /** Focus a task, then switch Android's global input display only if task focus stayed elsewhere. */
    private void focusTask(int taskId)throws Exception{
        int display=-1;
        for(Object root:(List<?>)taskClass.getMethod("getAllRootTaskInfos").invoke(tasks)){
            if(integer(root,"taskId")==taskId){display=integer(root,"displayId");break;}
        }
        if(display<0||info(display)==null)throw new IllegalStateException("Task display is unavailable");
        taskClass.getMethod("setFocusedTask",int.class).invoke(tasks,taskId);
        if(focusedTaskId()!=taskId){
            device.run("input","-d",Integer.toString(display),"tap","-10","-10");
        }
    }

    private int percentage(JSONObject request,boolean main) throws Exception {
        Object raw=request.get("percent");
        if(!(raw instanceof Number)) throw new IllegalArgumentException("A brightness percentage is required");
        double value=((Number)raw).doubleValue();
        if(!Double.isFinite(value)||value<0||value>100||value!=Math.rint(value))
            throw new IllegalArgumentException("Brightness must be a whole percentage from 0 to 100");
        return ControlLogic.percent((int)value,main);
    }

    private Object info(int id) throws Exception { return displayClass.getMethod("getDisplayInfo",int.class).invoke(displays,id); }
    private JSONObject systemUiShade(JSONObject request)throws Exception{
        int display=request.getInt("display");
        if(display<0||info(display)==null)throw new IllegalArgumentException("Display unavailable");
        boolean focused=FocusedShadeParser.isNotificationShadeFocused(device.windowDisplayFocusDump(),display);
        return new JSONObject().put("focusedShade",focused).put("display",display);
    }
    private static int integer(Object object,String field) throws Exception { return object.getClass().getField(field).getInt(object); }
    private static String string(Object object,String field) throws Exception {
        Object value=object.getClass().getField(field).get(object); return value==null?"":value.toString();
    }
    private float physicalRefresh(Object info)throws Exception{
        String identity=string(info,"uniqueId");
        if(identity.matches("local:[0-9]+"))try{
            long id=Long.parseLong(identity.substring(6));Class<?> binder=Class.forName("android.os.IBinder");
            Object token=surfaceClass.getMethod("getPhysicalDisplayToken",long.class).invoke(null,id);
            Object dynamic=surfaceClass.getMethod("getDynamicDisplayInfo",binder).invoke(null,token);
            int active=integer(dynamic,"activeDisplayModeId");
            for(Object mode:(Object[])dynamic.getClass().getField("supportedDisplayModes").get(dynamic))if(integer(mode,"id")==active)return mode.getClass().getField("refreshRate").getFloat(mode);
        }catch(Exception ignored){}
        return ((Number)info.getClass().getMethod("getRefreshRate").invoke(info)).floatValue();
    }
    private Object external(JSONObject request) throws Exception {
        int id=request.getInt("display");
        Object info=info(id);
        if(id<=0||info==null||integer(info,"type")!=2) throw new IllegalStateException("Connect an external display first");
        String identity=string(info,"uniqueId");
        if(!identity.equals(request.getString("identity"))) throw new IllegalStateException("Display changed; try again");
        return info;
    }

    private synchronized JSONObject dockedCanvas(JSONObject request)throws Exception{
        switch(request.getString("action")){
            case "status":return dockedCanvasStatus();
            case "release":validateDockedReleaseTarget(request);releaseDockedCanvas();return dockedCanvasStatus().put("released",true);
            case "apply":{
                Object output=external(request);
                String targetIdentity=string(output,"uniqueId");
                if(dockedCanvasPreview!=null){
                    if(dockedCanvasPreview.identity.equals(targetIdentity))restoreDockedCanvasPreview();
                    else dockedCanvasPreview=null;
                }
                String option=request.optString("optionId","tv_1080p");
                JSONObject applied=applyDockedCanvasChoice(request,option);
                dockedCanvasOption=option;
                return applied;
            }
            case "preview":{
                Object output=external(request);String targetIdentity=string(output,"uniqueId");
                String previousOption=null;
                if(dockedCanvasPreview!=null){
                    if(dockedCanvasPreview.identity.equals(targetIdentity)){previousOption=dockedCanvasPreview.previousOption;restoreDockedCanvasPreview();}
                    else dockedCanvasPreview=null;
                }else if(dockedCanvasLease.active()&&targetIdentity.equals(dockedCanvasIdentity))previousOption=dockedCanvasOption;
                String option=request.getString("optionId");
                JSONObject applied=applyDockedCanvasChoice(request,option);
                DockedCanvasPreview preview=new DockedCanvasPreview(integer(output,"displayId"),targetIdentity,
                    previousOption,UUID.randomUUID().toString(),option,applied.getInt("width"),applied.getInt("height"),
                    android.os.SystemClock.elapsedRealtime()+15000);
                dockedCanvasPreview=preview;
                return applied.put("transactionId",preview.transactionId).put("expiresAt",preview.deadline);
            }
            case "keep":{
                DockedCanvasPreview pending=requireDockedPreview(request.getString("transactionId"));
                Object output=external(request);
                if(integer(output,"displayId")!=pending.display||!string(output,"uniqueId").equals(pending.identity))
                    throw new IllegalStateException("Display changed; logical canvas preview was not kept");
                if(android.os.SystemClock.elapsedRealtime()>=pending.deadline){restoreDockedCanvasPreview();throw new IllegalStateException("The preview expired and was reverted");}
                dockedCanvasPreview=null;dockedCanvasOption=pending.optionId;
                return new JSONObject().put("transactionId",pending.transactionId).put("optionId",pending.optionId)
                    .put("display",pending.display).put("identity",pending.identity).put("width",pending.width).put("height",pending.height);
            }
            case "revert":{
                DockedCanvasPreview pending=requireDockedPreview(request.getString("transactionId"));
                Object output=external(request);
                if(integer(output,"displayId")!=pending.display||!string(output,"uniqueId").equals(pending.identity))
                    throw new IllegalStateException("Display changed; logical canvas preview was not reverted");
                restoreDockedCanvasPreview();
                Object primary=info(0);
                return new JSONObject().put("transactionId",pending.transactionId).put("optionId",pending.previousOption==null?JSONObject.NULL:pending.previousOption)
                    .put("reverted",true).put("display",pending.display).put("identity",pending.identity)
                    .put("width",primary==null?0:integer(primary,"logicalWidth")).put("height",primary==null?0:integer(primary,"logicalHeight"));
            }
            default:throw new IllegalArgumentException("Unknown docked-canvas action");
        }
    }

    private JSONObject applyDockedCanvasChoice(JSONObject request,String option)throws Exception{
        Object output=external(request);int displayId=integer(output,"displayId");String identity=string(output,"uniqueId");
        DimLayer oldDim=layers.get(displayId);int preservedDim=oldDim==null?100:oldDim.percent;
        prepareDockedExternal(displayId,true);
        output=external(request);
        int[] externalNative=physicalDimensions(output,displayId,integer(output,"rotation"));
        DisplayModePolicy.Canvas choice=DisplayModePolicy.mirroredCanvas(option,externalNative[0],externalNative[1]);
        if(choice==null)throw new IllegalArgumentException("Choose a supported mirrored canvas");
        Object primary=info(0);if(primary==null)throw new IllegalStateException("Primary display is unavailable");
        int rotation=integer(primary,"rotation");
        int[] logical=dockedCanvasLease.apply(identity,choice.width,choice.height,rotation);
        Object after=info(0);
        if(after==null||integer(after,"logicalWidth")!=logical[0]||integer(after,"logicalHeight")!=logical[1]){
            releaseDockedCanvas();throw new IOException("Android did not apply the requested primary canvas");
        }
        try{
            Object confirmedOutput=external(request);
            if(preservedDim<100)setDim(confirmedOutput,preservedDim);
            prepareDockedExternal(displayId,false);
            external(request);
        }catch(Exception failure){
            try{releaseDockedCanvas();}catch(Exception restoreFailure){failure.addSuppressed(restoreFailure);}
            throw failure;
        }
        dockedCanvasIdentity=identity;dockedCanvasDisplay=displayId;dockedCanvasWidth=logical[0];dockedCanvasHeight=logical[1];
        dockedCanvasNextRenewal=android.os.SystemClock.elapsedRealtime()+10000;
        return new JSONObject().put("optionId",choice.id).put("display",displayId).put("identity",identity)
            .put("width",logical[0]).put("height",logical[1]);
    }

    /** Retire only the exact old secondary HOME or DuoDash mirror root; fail closed on every other visible root. */
    private void prepareDockedExternal(int displayId,boolean beforeResize)throws Exception{
        String homePackage=TvProfilePolicy.defaultHomePackage(device.run("cmd","package","resolve-activity","--brief","-a","android.intent.action.MAIN","-c","android.intent.category.HOME"));
        String secondaryComponent=null;
        if(homePackage!=null){
            String candidates=device.run("cmd","package","query-activities","--brief","-a","android.intent.action.MAIN","-c","android.intent.category.SECONDARY_HOME");
            secondaryComponent=TvProfilePolicy.secondaryHomeComponent(homePackage,candidates);
            if(secondaryComponent!=null){
                String resolved=device.run("cmd","package","resolve-activity","--brief","-a","android.intent.action.MAIN","-c","android.intent.category.SECONDARY_HOME","-p",homePackage);
                if(!secondaryComponent.equals(TvProfilePolicy.secondaryHomeComponent(homePackage,resolved)))secondaryComponent=null;
            }
        }
        ComponentName secondary=secondaryComponent==null?null:ComponentName.unflattenFromString(secondaryComponent);
        List<Integer> legacyTasks=new ArrayList<>(),mirrorTasks=new ArrayList<>();boolean hasOtherVisible=false;
        List<?> roots=(List<?>)taskClass.getMethod("getAllRootTaskInfos").invoke(tasks);
        for(Object root:roots){
            if(integer(root,"displayId")!=displayId||!root.getClass().getField("visible").getBoolean(root))continue;
            int taskId=integer(root,"taskId");
            ComponentName top=(ComponentName)root.getClass().getField("topActivity").get(root);
            boolean mirror=top!=null&&top.getPackageName().equals("dev.nova.deck")&&top.getClassName().equals("dev.nova.deck.MirrorActivity");
            if(top!=null&&secondary!=null&&secondary.equals(top))legacyTasks.add(taskId);
            else if(mirror&&beforeResize){legacyTasks.add(taskId);mirrorTasks.add(taskId);}
            else if(mirror&&!beforeResize){ /* The known DuoDash mirror remains valid after its layer is rebuilt. */ }
            else hasOtherVisible=true;
        }
        if(hasOtherVisible)throw new IllegalStateException("Close the visible app on the external display before enabling Docked mode");
        if(!mirrorTasks.isEmpty()&&layers.containsKey(displayId))removeLayer(displayId);
        for(int taskId:legacyTasks)if(taskExists(taskId))taskClass.getMethod("removeTask",int.class).invoke(tasks,taskId);
        if(!legacyTasks.isEmpty()){
            for(Object root:(List<?>)taskClass.getMethod("getAllRootTaskInfos").invoke(tasks)){
                if(integer(root,"displayId")==displayId&&root.getClass().getField("visible").getBoolean(root)){
                    ComponentName top=(ComponentName)root.getClass().getField("topActivity").get(root);
                    boolean mirror=top!=null&&top.getPackageName().equals("dev.nova.deck")&&top.getClassName().equals("dev.nova.deck.MirrorActivity");
                    if(top==null||!(secondary!=null&&secondary.equals(top))&&!(mirror&&!beforeResize))
                        throw new IllegalStateException("An app is still visible on the external display; Docked mode was not applied");
                }
            }
        }
    }

    private boolean taskExists(int taskId)throws Exception{
        for(Object root:(List<?>)taskClass.getMethod("getAllRootTaskInfos").invoke(tasks))if(integer(root,"taskId")==taskId)return true;
        return false;
    }

    private void restoreDockedCanvasPreview()throws Exception{
        DockedCanvasPreview pending=dockedCanvasPreview;if(pending==null)return;
        if(pending.previousOption==null){releaseDockedCanvas();}
        else{
            JSONObject target=new JSONObject().put("display",pending.display).put("identity",pending.identity);
            applyDockedCanvasChoice(target,pending.previousOption);dockedCanvasOption=pending.previousOption;
            dockedCanvasPreview=null;
        }
    }

    private void expireDockedCanvasPreview(){
        if(dockedCanvasPreview==null||android.os.SystemClock.elapsedRealtime()<dockedCanvasPreview.deadline)return;
        try{restoreDockedCanvasPreview();}catch(Exception failure){android.util.Log.w("NovaDeckBridge","Docked canvas preview timeout restore failed");}
    }

    private DockedCanvasPreview requireDockedPreview(String transactionId)throws Exception{
        if(dockedCanvasPreview==null||!dockedCanvasPreview.transactionId.equals(transactionId))
            throw new IllegalStateException("Logical canvas preview is no longer active");
        if(android.os.SystemClock.elapsedRealtime()>=dockedCanvasPreview.deadline){restoreDockedCanvasPreview();throw new IllegalStateException("The preview expired and was reverted");}
        return dockedCanvasPreview;
    }

    private void releaseDockedCanvas()throws Exception{
        dockedCanvasLease.release();
        dockedCanvasPreview=null;dockedCanvasIdentity=null;dockedCanvasOption=null;dockedCanvasDisplay=-1;
        dockedCanvasWidth=0;dockedCanvasHeight=0;dockedCanvasNextRenewal=0;
    }

    private JSONObject dockedCanvasStatus()throws Exception{
        JSONObject result=new JSONObject().put("active",dockedCanvasLease.active());
        if(dockedCanvasLease.active())result.put("identity",dockedCanvasLease.identity()).put("display",dockedCanvasDisplay)
            .put("optionId",dockedCanvasOption==null?JSONObject.NULL:dockedCanvasOption)
            .put("width",dockedCanvasWidth).put("height",dockedCanvasHeight);
        if(dockedCanvasPreview!=null)result.put("transactionId",dockedCanvasPreview.transactionId).put("expiresAt",dockedCanvasPreview.deadline)
            .put("optionId",dockedCanvasPreview.optionId).put("width",dockedCanvasPreview.width).put("height",dockedCanvasPreview.height);
        return result;
    }

    private void validateDockedReleaseTarget(JSONObject request)throws Exception{
        if(!request.has("display")||!request.has("identity"))return;
        String requested=request.optString("identity","");
        if(!requested.isEmpty()&&externalIdentityPresent(requested)){
            external(request);
            if(dockedCanvasLease.active()&&!requested.equals(dockedCanvasLease.identity()))
                throw new IllegalStateException("Display changed; docked canvas lease was not released");
        }else if(dockedCanvasLease.active()&&externalIdentityPresent(dockedCanvasLease.identity())
            &&!requested.equals(dockedCanvasLease.identity()))
            throw new IllegalStateException("The active external display changed before release");
    }

    private static final class DockedCanvasPreview{
        final int display,width,height;final String identity,previousOption,transactionId,optionId;final long deadline;
        DockedCanvasPreview(int display,String identity,String previousOption,String transactionId,String optionId,int width,int height,long deadline){
            this.display=display;this.identity=identity;this.previousOption=previousOption;this.transactionId=transactionId;
            this.optionId=optionId;this.width=width;this.height=height;this.deadline=deadline;
        }
    }

    private synchronized JSONObject panelPower(JSONObject request)throws Exception{
        String action=request.getString("action");
        if(action.equals("off")||action.equals("resume")){
            Object target=external(request);
            if(!device.panelPowerSupported())throw new IllegalStateException("Native handheld screen-off is unavailable");
            String targetIdentity=string(target,"uniqueId");
            if(panelPowerLease&&!panelPowerIdentity.equals(targetIdentity))throw new IllegalStateException("The external display changed before screen-off");
            if(!panelPowerLease){
                panelPowerBaseline=device.closeScreenWhenOutputVideo();
                panelPowerIdentity=targetIdentity;panelPowerLease=true;
            }
            panelPowerDesiredOff=true;
            try{device.armPanelPowerFailsafe();}
            catch(Exception failure){device.cancelPanelPowerFailsafe();panelPowerLease=false;panelPowerIdentity="";panelPowerBaseline=null;throw failure;}
            try{device.closeScreenWhenOutputVideo("1");}
            catch(Exception failure){device.cancelPanelPowerFailsafe();panelPowerLease=false;panelPowerIdentity="";panelPowerBaseline=null;throw failure;}
            panelPowerLastWritten="1";panelPowerNextRenewal=android.os.SystemClock.elapsedRealtime()+10000;
            return new JSONObject().put("applied",true).put("value",1).put("baseline",panelPowerBaseline==null?JSONObject.NULL:panelPowerBaseline).put("identity",panelPowerIdentity);
        }
        if(action.equals("wake")){
            panelPowerDesiredOff=request.optBoolean("desiredOff",panelPowerDesiredOff);
            if(panelPowerLease&&!panelPowerIdentity.equals(request.optString("identity")))restorePanelPowerLease(false);
            String current=device.closeScreenWhenOutputVideo();
            if(!panelPowerLease&&"1".equals(current)){
                Object target=external(request);
                panelPowerBaseline=current;panelPowerIdentity=string(target,"uniqueId");panelPowerLastWritten="1";panelPowerLease=true;
            }
            if(panelPowerLease&&TvProfilePolicy.stillOwnsSetting(current,panelPowerLastWritten)||!panelPowerLease&&device.panelPowerFailsafeActive()&&"1".equals(current)){
                device.closeScreenWhenOutputVideo("0");
                if(!"0".equals(device.closeScreenWhenOutputVideo()))throw new IllegalStateException("Android did not wake the handheld screen");
                if(panelPowerLease)panelPowerLastWritten="0";
                device.cancelPanelPowerFailsafe();
            }
            String value=device.closeScreenWhenOutputVideo();
            return new JSONObject().put("applied",false).put("value",value==null?JSONObject.NULL:value);
        }
        if(action.equals("release")){
            panelPowerDesiredOff=request.optBoolean("desiredOff",panelPowerDesiredOff);
            restorePanelPowerLease(true);
            return new JSONObject().put("released",true);
        }
        throw new IllegalArgumentException("Unknown panel-power action");
    }

    private boolean externalIdentityPresent(String identity){
        if(identity==null||identity.isEmpty())return false;
        try{for(int id:(int[])displayClass.getMethod("getDisplayIds").invoke(displays)){
            Object screen=info(id);if(id>0&&screen!=null&&integer(screen,"type")==2&&identity.equals(string(screen,"uniqueId")))return true;
        }}catch(Exception ignored){}
        return false;
    }

    private synchronized void restorePanelPowerLease(boolean restoreBaseline){
        if(!panelPowerLease)return;
        boolean released=false;
        try{
            String current=device.closeScreenWhenOutputVideo();
            if(!TvProfilePolicy.stillOwnsSetting(current,panelPowerLastWritten)){
                released=true;
            }else{
                device.closeScreenWhenOutputVideo("0");
                if(!"0".equals(device.closeScreenWhenOutputVideo()))return;
                boolean connected=externalIdentityPresent(panelPowerIdentity);
                boolean panelOn=device.panelBacklightOn();
                if(restoreBaseline&&(!connected||panelOn)&&TvProfilePolicy.mayRestoreBaseline(connected,panelPowerBaseline,panelOn)){
                    if(panelPowerBaseline==null)device.closeScreenWhenOutputVideo(null);
                    else{
                        device.closeScreenWhenOutputVideo(panelPowerBaseline);
                        if("1".equals(panelPowerBaseline)){
                            Thread.sleep(250);
                            if(!device.panelBacklightOn())device.closeScreenWhenOutputVideo("0");
                        }
                    }
                }
                released=true;
            }
        }catch(Exception failure){System.err.println("Panel wake restore failed: "+failure.getClass().getSimpleName());}
        if(released)try{device.cancelPanelPowerFailsafe();}catch(Exception ignored){}
        if(released){panelPowerLease=false;panelPowerIdentity="";panelPowerBaseline=null;panelPowerLastWritten=null;panelPowerNextRenewal=0;}
    }

    private JSONObject status() throws Exception {
        JSONArray screenList=new JSONArray(), taskJson=new JSONArray();
        int[] ids=(int[])displayClass.getMethod("getDisplayIds").invoke(displays);
        Set<Integer> connected=new HashSet<>();
        for(int id:ids) {
            Object info=info(id);
            if(info==null) continue;
            if(id==0)try{Set<Integer> rates=new TreeSet<>();for(Object mode:(Object[])info.getClass().getField("supportedModes").get(info)){int rate=Math.round(((Number)mode.getClass().getMethod("getRefreshRate").invoke(mode)).floatValue());if(rate>=24&&rate<=240)rates.add(rate);}device.refreshRates(rates);}catch(Exception ignored){}
            connected.add(id);
            String product="";
            try { product=string(info,"deviceProductInfo"); } catch(Exception ignored) { }
            float refresh=60;
            try { refresh=physicalRefresh(info); } catch(Exception ignored) { }
            int rotation=0, nativeWidth=integer(info,"logicalWidth"), nativeHeight=integer(info,"logicalHeight");
            try { rotation=integer(info,"rotation"); } catch(Exception ignored) { }
            int[] physical=physicalDimensions(info,id,rotation);nativeWidth=physical[0];nativeHeight=physical[1];
            Map<String,LogicalCanvas> canvasChoices=LogicalCanvas.choices(nativeWidth,nativeHeight);
            screenList.put(new JSONObject().put("id",id).put("identity",string(info,"uniqueId"))
                .put("name",string(info,"name")).put("product",product).put("type",integer(info,"type"))
                .put("width",integer(info,"logicalWidth")).put("height",integer(info,"logicalHeight"))
                .put("rotation",rotation).put("nativeWidth",nativeWidth).put("nativeHeight",nativeHeight)
                .put("canvasChoices",LogicalCanvas.jsonChoices(canvasChoices))
                .put("layerStack",integer(info,"layerStack")).put("refresh",refresh));
        }
        for(int id:new ArrayList<>(layers.keySet())) {
            Object current=connected.contains(id)?info(id):null;
            if(current==null||!layers.get(id).identity.equals(string(current,"uniqueId"))) removeLayer(id);
            else setDim(current,layers.get(id).percent);
        }
        for(int i=0;i<screenList.length();i++){
            JSONObject screen=screenList.getJSONObject(i);DimLayer layer=layers.get(screen.getInt("id"));
            screen.put("dimPercent",layer==null?100:layer.percent);
        }
        List<ControlLogic.Task> currentTasks=taskList();
        for(ControlLogic.Task task:currentTasks) taskJson.put(new JSONObject().put("id",task.id).put("display",task.display)
            .put("package",task.pkg).put("visible",task.visible).put("standard",task.standard));
        float brightness=((Number)displayClass.getMethod("getBrightness",int.class).invoke(displays,0)).floatValue();
        int external=-1;for(int i=0;i<screenList.length();i++)if(screenList.getJSONObject(i).optInt("type")==2){external=screenList.getJSONObject(i).getInt("id");break;}
        JSONObject controls;try{controls=device.status();}catch(Exception failure){controls=new JSONObject();}
        return new JSONObject().put("displays",screenList).put("tasks",taskJson).put("main",Math.round(brightness*100)).put("version",2)
            .put("bridgeBuild",BRIDGE_BUILD).put("swapStrategy",SWAP_STRATEGY)
            .put("controls",controls).put("telemetry",device.telemetry()).put("audio",audio.status(currentTasks,external)).put("fps",fps.status(currentTasks,external)).put("gamepadMonitor",gamepad.available())
            .put("panelPowerSupported",device.panelPowerSupported()).put("panelPowerValue",device.panelScreenOffValue())
            .put("panelPowerNativeBaselineOff",panelPowerLease?"1".equals(panelPowerBaseline):device.panelScreenOffValue()==1)
            .put("dockedCanvas",dockedCanvasStatus());
    }

    private JSONObject previewCanvas(JSONObject request)throws Exception{
        int id=request.getInt("display");Object display=canvasDisplay(id,request.getString("identity"));
        if(canvasPreview!=null){restoreCanvas(canvasPreview);canvasPreview=null;}
        CanvasPreview previous=readCanvas(id,request.getString("identity"),display);
        LogicalCanvas choice=canvasChoices(display,id).get(request.getString("optionId"));
        if(choice==null)throw new IllegalArgumentException("Choose a supported logical canvas");
        setCanvas(id,display,choice);
        Object after=canvasDisplay(id,request.getString("identity"));
        int[] viewSize={choice.width,choice.height};
        if(integer(after,"logicalWidth")!=viewSize[0]||integer(after,"logicalHeight")!=viewSize[1]){
            restoreCanvas(previous);throw new IOException("Android did not apply this logical canvas");
        }
        previous.optionId=choice.id;previous.width=viewSize[0];previous.height=viewSize[1];
        previous.transactionId=UUID.randomUUID().toString();previous.deadline=android.os.SystemClock.elapsedRealtime()+15000;
        canvasPreview=previous;
        return new JSONObject().put("transactionId",previous.transactionId).put("optionId",choice.id)
            .put("display",id).put("identity",previous.identity).put("width",viewSize[0]).put("height",viewSize[1]).put("expiresAt",previous.deadline);
    }
    private JSONObject keepCanvas(JSONObject request)throws Exception{
        CanvasPreview pending=requirePreview(request.getString("transactionId"));
        canvasDisplay(pending.display,pending.identity);
        if(android.os.SystemClock.elapsedRealtime()>=pending.deadline){restoreCanvas(pending);canvasPreview=null;throw new IllegalStateException("The preview expired and was reverted");}
        canvasPreview=null;
        return new JSONObject().put("optionId",pending.optionId).put("width",pending.width).put("height",pending.height);
    }
    private JSONObject revertCanvas(JSONObject request)throws Exception{
        CanvasPreview pending=requirePreview(request.getString("transactionId"));restoreCanvas(pending);canvasPreview=null;
        return new JSONObject().put("reverted",true);
    }
    private JSONObject applySavedCanvas(JSONObject request)throws Exception{
        int id=request.getInt("display");Object display=canvasDisplay(id,request.getString("identity"));
        if(canvasPreview!=null){restoreCanvas(canvasPreview);canvasPreview=null;display=canvasDisplay(id,request.getString("identity"));}
        LogicalCanvas choice=canvasChoices(display,id).get(request.optString("optionId","native"));
        if(choice==null)throw new IllegalArgumentException("Saved logical canvas is unavailable on this display");
        setCanvas(id,display,choice);
        return new JSONObject().put("width",integer(info(id),"logicalWidth")).put("height",integer(info(id),"logicalHeight"));
    }
    private synchronized void expireCanvasPreview(){
        if(canvasPreview==null||android.os.SystemClock.elapsedRealtime()<canvasPreview.deadline)return;
        try{restoreCanvas(canvasPreview);}catch(Exception failure){android.util.Log.w("NovaDeckBridge","Logical canvas timeout restore failed");}
        canvasPreview=null;
    }
    private CanvasPreview requirePreview(String id){
        if(canvasPreview==null||!canvasPreview.transactionId.equals(id))throw new IllegalStateException("Logical canvas preview is no longer active");
        return canvasPreview;
    }
    private Object canvasDisplay(int id,String identity)throws Exception{
        Object current=info(id);
        if(current==null||id<0||!string(current,"uniqueId").equals(identity))
            throw new IllegalStateException("Display changed; logical canvas was not applied");
        if(id!=0&&integer(current,"type")!=2)throw new IllegalStateException("Select a connected display");
        return current;
    }
    private Map<String,LogicalCanvas> canvasChoices(Object display,int id)throws Exception{
        int rotation=integer(display,"rotation");int[] size=physicalDimensions(display,id,rotation);
        return LogicalCanvas.choices(size[0],size[1]);
    }
    private int[] physicalDimensions(Object display,int displayId,int rotation){
        int width=0,height=0;
        try{
            android.graphics.Point initial=new android.graphics.Point();
            windowManager.getClass().getMethod("getInitialDisplaySize",int.class,android.graphics.Point.class).invoke(windowManager,displayId,initial);
            width=initial.x;height=initial.y;
        }catch(Exception ignored){}
        if(width<=0||height<=0)return new int[]{0,0};
        if(rotation%2!=0){int swap=width;width=height;height=swap;}
        return new int[]{width,height};
    }
    private CanvasPreview readCanvas(int id,String identity,Object display)throws Exception{
        return new CanvasPreview(id,identity,currentOverride(id));
    }
    private void setCanvas(int id,Object display,LogicalCanvas choice)throws Exception{
        String override=currentOverride(id);
        if(choice.id.equals("native")){if(override!=null)device.run("wm","size","reset","-d",Integer.toString(id));return;}
        int[] natural=LogicalCanvas.toNaturalSize(choice.width,choice.height,integer(display,"rotation"));
        String requested=natural[0]+"x"+natural[1];if(!requested.equals(override))device.run("wm","size",requested,"-d",Integer.toString(id));
    }
    private String currentOverride(int id)throws Exception{
        String output=device.run("wm","size","-d",Integer.toString(id));
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("(?m)^Override size: ([0-9]+x[0-9]+)$").matcher(output);
        return match.find()?match.group(1):null;
    }
    private void restoreCanvas(CanvasPreview previous)throws Exception{
        Object current=info(previous.display);
        if(current==null||!string(current,"uniqueId").equals(previous.identity))return;
        if(previous.oldOverride==null)device.run("wm","size","reset","-d",Integer.toString(previous.display));
        else device.run("wm","size",previous.oldOverride,"-d",Integer.toString(previous.display));
    }
    private static final class CanvasPreview{
        final int display;final String identity,oldOverride;String transactionId,optionId;int width,height;long deadline;
        CanvasPreview(int display,String identity,String oldOverride){this.display=display;this.identity=identity;this.oldOverride=oldOverride;}
    }

    @SuppressWarnings("unchecked") private List<ControlLogic.Task> taskList() throws Exception {
        List<?> rootTasks=(List<?>)taskClass.getMethod("getAllRootTaskInfos").invoke(tasks);
        List<ControlLogic.Task> result=new ArrayList<>();
        for(Object root:rootTasks) {
            ComponentName top=(ComponentName)root.getClass().getField("topActivity").get(root);
            if(top==null) continue;
            Object config=root.getClass().getField("configuration").get(root);
            Object window=config.getClass().getField("windowConfiguration").get(config);
            int activityType=(Integer)window.getClass().getMethod("getActivityType").invoke(window);
            boolean visible=root.getClass().getField("visible").getBoolean(root);
            result.add(new ControlLogic.Task(integer(root,"taskId"),integer(root,"displayId"),top.getPackageName(),visible,activityType==1));
        }
        return result;
    }

    private JSONObject clearRecentTasks()throws Exception{
        Set<String> homePackages=resolvedHomePackages();
        Object recentSlice=taskClass.getMethod("getRecentTasks",int.class,int.class,int.class)
                .invoke(tasks,1000,0,android.os.Process.myUid()/100000);
        if(recentSlice==null)throw new IOException("Could not read recent tasks; nothing was cleared");
        Object recentValue=recentSlice instanceof List?recentSlice:recentSlice.getClass().getMethod("getList").invoke(recentSlice);
        if(!(recentValue instanceof List))throw new IOException("Could not read recent tasks; nothing was cleared");
        List<?> running;
        try{
            running=(List<?>)taskClass.getMethod("getTasks",int.class,boolean.class,boolean.class)
                    .invoke(tasks,Integer.MAX_VALUE,false,false);
        }catch(NoSuchMethodException newerApi){
            running=(List<?>)taskClass.getMethod("getTasks",int.class,boolean.class,boolean.class,int.class)
                    .invoke(tasks,Integer.MAX_VALUE,false,false,-1);
        }
        if(running==null)throw new IOException("Could not read running apps; nothing was cleared");
        List<RecentTaskPolicy.Task> roots=RecentTaskPolicy.combine(recentTaskMetadata((List<?>)recentValue),recentTaskMetadata(running));
        List<RecentTaskPolicy.Task> candidates=RecentTaskPolicy.removable(roots,homePackages);
        int removed=0,failed=0;
        for(RecentTaskPolicy.Task task:candidates){
            try{Object result=taskClass.getMethod("removeTask",int.class).invoke(tasks,task.id);if(Boolean.TRUE.equals(result))removed++;else failed++;}
            catch(Exception ignored){failed++;}
        }
        String message=failed==0?(removed==0?"No recent apps to clear":"Cleared "+removed+" recent apps")
                :"Cleared "+removed+" recent apps; "+failed+" could not be cleared";
        return new JSONObject().put("removed",removed).put("failed",failed).put("message",message);
    }

    private List<RecentTaskPolicy.Task> recentTaskMetadata(List<?> tasks)throws Exception{
        List<RecentTaskPolicy.Task> result=new ArrayList<>();
        for(Object root:tasks){
            ComponentName top=taskComponent(root,"topActivity");
            ComponentName base=taskComponent(root,"baseActivity");
            if(top==null)top=base;if(base==null)base=top;
            if(top==null)continue;
            Object configuration=root.getClass().getField("configuration").get(root);
            Object window=configuration.getClass().getField("windowConfiguration").get(configuration);
            int activityType=(Integer)window.getClass().getMethod("getActivityType").invoke(window);
            int id=integer(root,"taskId");
            result.add(new RecentTaskPolicy.Task(id,integer(root,"displayId"),activityType,top.getPackageName(),base.getPackageName()));
        }
        return result;
    }

    private ComponentName taskComponent(Object task,String field){
        try{Object value=task.getClass().getField(field).get(task);return value instanceof ComponentName?(ComponentName)value:null;}
        catch(Exception ignored){return null;}
    }

    private Set<String> resolvedHomePackages()throws Exception{
        String output=device.run("cmd","package","resolve-activity","--brief","-a","android.intent.action.MAIN","-c","android.intent.category.HOME");
        Set<String> packages=new HashSet<>();
        for(String line:output.split("\\r?\\n")){
            String component=line.trim();int slash=component.indexOf('/');
            if(slash<=0||slash==component.length()-1)continue;
            String name=component.substring(0,slash);
            if(name.matches("[A-Za-z0-9_.$]+"))packages.add(name);
        }
        if(packages.isEmpty())throw new IOException("Could not identify the home app; recent tasks were left alone");
        return packages;
    }

    private int mirrorTask(int id) throws Exception {return mirrorTask(id,false);}
    private int mirrorTask(int id,boolean visibleOnly) throws Exception {
        List<?> roots=(List<?>)taskClass.getMethod("getAllRootTaskInfos").invoke(tasks);
        for(Object root:roots){
            ComponentName top=(ComponentName)root.getClass().getField("topActivity").get(root);
            if(integer(root,"displayId")==id&&top!=null&&top.getClassName().equals("dev.nova.deck.MirrorActivity")
                &&(!visibleOnly||root.getClass().getField("visible").getBoolean(root)))return integer(root,"taskId");
        }
        return -1;
    }

    private void startUpperActivity(int display,String component) throws Exception {
        Object focused=taskClass.getMethod("getFocusedRootTaskInfo").invoke(tasks);
        int previous=focused==null?-1:integer(focused,"taskId");
        Process process=new ProcessBuilder("am","start","--display",Integer.toString(display),"-n",
            component,"-f","0x18000000").redirectErrorStream(true).start();
        // The command is fixed except for a validated, connected external display ID.
        if(!process.waitFor(3,TimeUnit.SECONDS)) { process.destroy(); throw new IOException("Upper display is still opening"); }
        if(process.exitValue()!=0) throw new IOException("Could not prepare the upper display");
        if(component.endsWith("/.MirrorActivity")&&previous>=0){
            taskClass.getMethod("setFocusedTask",int.class).invoke(tasks,previous);
        }
    }

    /** Reads the actual physical composition, so Presentation windows count correctly. */
    private int compositionStack(Object info) throws Exception {
        if(android.os.SystemClock.elapsedRealtime()-compositionReadAt>500){
            Process process=new ProcessBuilder("sh","-c","dumpsys display | grep -E 'DisplayDeviceInfo\\{|mCurrentLayerStack='").redirectErrorStream(true).start();
            if(!process.waitFor(2,TimeUnit.SECONDS)){process.destroy();throw new IOException("Display composition timed out");}
            String text=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            if(text.length()>32768)throw new IOException("Unexpected display configuration");
            compositionStacks.clear();String identity=null;
            for(String line:text.split("\n")){
                if(line.contains("DisplayDeviceInfo{")){
                    java.util.regex.Matcher match=java.util.regex.Pattern.compile("uniqueId=\"([^\"]+)\"").matcher(line);
                    identity=match.find()?match.group(1):null;
                }else if(identity!=null&&line.contains("mCurrentLayerStack=")){
                    compositionStacks.put(identity,Integer.parseInt(line.trim().split("=",2)[1]));identity=null;
                }
            }
            compositionReadAt=android.os.SystemClock.elapsedRealtime();
        }
        Integer stack=compositionStacks.get(string(info,"uniqueId"));
        if(stack==null)throw new IOException("Display composition is not ready yet");
        return stack;
    }

    private Object createMirror(int id) throws Exception {
        Object mirror=surfaceClass.getConstructor().newInstance();
        Object windowService=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
        boolean created=(Boolean)Class.forName("android.view.IWindowManager").getMethod("mirrorDisplay",int.class,surfaceClass).invoke(windowService,0,mirror);
        if(!created){surfaceClass.getMethod("release").invoke(mirror);throw new IOException("Could not mirror the main display");}
        return mirror;
    }

    private boolean independentWindow(int display) throws Exception {
        Process process=new ProcessBuilder("sh","-c","dumpsys window windows | grep -E '^  Window #[0-9]|mDisplayId=|mAttrs=|mHasSurface=|isOnScreen='").redirectErrorStream(true).start();
        if(!process.waitFor(2,TimeUnit.SECONDS)){process.destroy();throw new IOException("Window information timed out");}
        String text=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        if(text.length()>65536)throw new IOException("Unexpected window configuration");
        for(String block:text.split("(?m)(?=^  Window #[0-9])")){
            if(block.contains(".MirrorActivity"))continue;
            if(block.contains("mDisplayId="+display+" ")&&block.contains("mHasSurface=true")&&block.contains("isOnScreen=true")){
                if(java.util.regex.Pattern.compile("ty=(BASE_APPLICATION|APPLICATION|APPLICATION_ATTACHED_DIALOG|PRESENTATION|PRIVATE_PRESENTATION)(?:\\s|\\})")
                        .matcher(block).find())return true;
            }
        }
        return false;
    }

    private boolean hasExternalAppTask(int display) throws Exception {
        List<ControlLogic.Task> current=taskList();
        Set<Integer> present=new HashSet<>();boolean found=false;
        for(ControlLogic.Task task:current){
            present.add(task.id);
            if(task.display==display&&ControlLogic.externalUserTask(task))found=true;
        }
        for(Iterator<Map.Entry<Integer,Integer>> iterator=movedTaskDestinations.entrySet().iterator();iterator.hasNext();){
            Map.Entry<Integer,Integer> moved=iterator.next();ControlLogic.Task currentTask=null;
            for(ControlLogic.Task task:current)if(task.id==moved.getKey()){currentTask=task;break;}
            if(!present.contains(moved.getKey())||currentTask==null||currentTask.display!=moved.getValue())iterator.remove();
            else if(moved.getValue()==display&&ControlLogic.externalUserTask(currentTask))found=true;
        }
        return found;
    }

    private void setDim(Object info,int percent) throws Exception {
        int id=integer(info,"displayId"), stack=integer(info,"layerStack");
        String identity=string(info,"uniqueId");
        if(id<=0||stack==0) throw new IllegalStateException("Upper display required");
        if(percent==100) { removeLayer(id); return; }
        int existingMirror=mirrorTask(id);
        int visibleMirror=mirrorTask(id,true);
        boolean appTaskPresent=hasExternalAppTask(id);
        boolean mirrored=ControlLogic.shouldMirrorExternal(compositionStack(info)==0,visibleMirror>=0,independentWindow(id),appTaskPresent);
        if(mirrored&&existingMirror<0){
            startUpperActivity(id,"dev.nova.deck/.MirrorActivity");
            compositionReadAt=0;
            existingMirror=mirrorTask(id);
        }
        DimLayer layer=layers.get(id);
        if(layer!=null&&!layer.identity.equals(identity)) { removeLayer(id); layer=null; }
        if(layer==null) {
            Class<?> builderClass=Class.forName("android.view.SurfaceControl$Builder");
            Object builder=builderClass.getConstructor().newInstance();
            builderClass.getMethod("setName",String.class).invoke(builder,"DuoDash dim "+id);
            builderClass.getMethod("setColorLayer").invoke(builder);
            layer=new DimLayer(identity,builderClass.getMethod("build").invoke(builder));
            layers.put(id,layer);
        }
        layer.percent=percent;
        Object transaction=transactionClass.getConstructor().newInstance();
        try {
            if(mirrored){
                if(layer.mirrorRoot==null){
                    Class<?> builderClass=Class.forName("android.view.SurfaceControl$Builder");
                    Object builder=builderClass.getConstructor().newInstance();
                    builderClass.getMethod("setName",String.class).invoke(builder,"DuoDash mirror scene "+id);
                    builderClass.getMethod("setContainerLayer").invoke(builder);
                    layer.mirrorRoot=builderClass.getMethod("build").invoke(builder);
                }
                if(layer.mirror==null)layer.mirror=createMirror(id);
                Object source=info(0);
                float width=integer(source,"logicalWidth"),height=integer(source,"logicalHeight");
                float targetWidth=integer(info,"logicalWidth"),targetHeight=integer(info,"logicalHeight");
                float scale=Math.min(targetWidth/width,targetHeight/height);
                transactionClass.getMethod("setLayerStack",surfaceClass,int.class).invoke(transaction,layer.mirrorRoot,stack);
                transactionClass.getMethod("setLayer",surfaceClass,int.class).invoke(transaction,layer.mirrorRoot,1);
                transactionClass.getMethod("show",surfaceClass).invoke(transaction,layer.mirrorRoot);
                transactionClass.getMethod("reparent",surfaceClass,surfaceClass).invoke(transaction,layer.mirror,layer.mirrorRoot);
                transactionClass.getMethod("setLayer",surfaceClass,int.class).invoke(transaction,layer.mirror,0);
                transactionClass.getMethod("setMatrix",surfaceClass,float.class,float.class,float.class,float.class).invoke(transaction,layer.mirror,scale,0f,0f,scale);
                transactionClass.getMethod("setPosition",surfaceClass,float.class,float.class).invoke(transaction,layer.mirror,(targetWidth-width*scale)/2,(targetHeight-height*scale)/2);
                transactionClass.getMethod("setWindowCrop",surfaceClass,int.class,int.class).invoke(transaction,layer.mirror,(int)width,(int)height);
                transactionClass.getMethod("show",surfaceClass).invoke(transaction,layer.mirror);
            }else if(!mirrored&&layer.mirror!=null){
                transactionClass.getMethod("remove",surfaceClass).invoke(transaction,layer.mirror);
                if(layer.mirrorRoot!=null)transactionClass.getMethod("remove",surfaceClass).invoke(transaction,layer.mirrorRoot);
                transactionClass.getMethod("apply").invoke(transaction);
                surfaceClass.getMethod("release").invoke(layer.mirror);layer.mirror=null;
                if(layer.mirrorRoot!=null){surfaceClass.getMethod("release").invoke(layer.mirrorRoot);layer.mirrorRoot=null;}
            }
            if(!mirrored&&existingMirror>=0)taskClass.getMethod("removeTask",int.class).invoke(tasks,existingMirror);
            transactionClass.getMethod("setLayerStack",surfaceClass,int.class).invoke(transaction,layer.surface,stack);
            transactionClass.getMethod("setLayer",surfaceClass,int.class).invoke(transaction,layer.surface,Integer.MAX_VALUE);
            transactionClass.getMethod("setColor",surfaceClass,float[].class).invoke(transaction,layer.surface,new float[]{0,0,0});
            int extent=Math.max(integer(info,"logicalWidth"),integer(info,"logicalHeight"));
            transactionClass.getMethod("setWindowCrop",surfaceClass,int.class,int.class).invoke(transaction,layer.surface,extent,extent);
            transactionClass.getMethod("setAlpha",surfaceClass,float.class).invoke(transaction,layer.surface,ControlLogic.dimAlpha(percent));
            transactionClass.getMethod("show",surfaceClass).invoke(transaction,layer.surface);
            transactionClass.getMethod("apply").invoke(transaction);
        } catch(Exception failure){removeLayer(id);throw failure;}
        finally { transactionClass.getMethod("close").invoke(transaction); }
    }
    private void removeLayer(int display) {
        DimLayer layer=layers.remove(display);
        if(layer==null) return;
        try {
            Object tx=transactionClass.getConstructor().newInstance();
            try {
                transactionClass.getMethod("remove",surfaceClass).invoke(tx,layer.surface);
                if(layer.mirror!=null)transactionClass.getMethod("remove",surfaceClass).invoke(tx,layer.mirror);
                if(layer.mirrorRoot!=null)transactionClass.getMethod("remove",surfaceClass).invoke(tx,layer.mirrorRoot);
                transactionClass.getMethod("apply").invoke(tx);
            }
            finally { transactionClass.getMethod("close").invoke(tx); }
        } catch(Exception error) { System.err.println("Dimming cleanup: "+error.getClass().getSimpleName()); }
        finally {
            try { surfaceClass.getMethod("release").invoke(layer.surface); } catch(Exception ignored) { }
            if(layer.mirror!=null){
                try{surfaceClass.getMethod("release").invoke(layer.mirror);}catch(Exception ignored){}
            }
            if(layer.mirrorRoot!=null){try{surfaceClass.getMethod("release").invoke(layer.mirrorRoot);}catch(Exception ignored){}}
            try{int task=mirrorTask(display);if(task>=0)taskClass.getMethod("removeTask",int.class).invoke(tasks,task);}catch(Exception ignored){}
        }
    }
    private synchronized void clearLayers(boolean restoreBaseline) {
        restorePanelPowerLease(restoreBaseline);
        for(int id:new ArrayList<>(layers.keySet())) removeLayer(id);
        if(canvasPreview!=null)try{restoreCanvas(canvasPreview);}catch(Exception ignored){}
        canvasPreview=null;
        try{releaseDockedCanvas();}catch(Exception failure){android.util.Log.w("NovaDeckBridge","Docked canvas cleanup restore failed");}
        if(temporaryMainBrightness)try{displayClass.getMethod("setTemporaryBrightness",int.class,float.class).invoke(displays,0,Float.NaN);}catch(Exception ignored){}
        temporaryMainBrightness=false;audio.reset();device.reset();if(gamepad!=null)gamepad.record(false);
    }
    private static final class DimLayer {
        final String identity; final Object surface;
        Object mirror,mirrorRoot;int percent;
        DimLayer(String identity,Object surface) { this.identity=identity; this.surface=surface; }
    }
}
