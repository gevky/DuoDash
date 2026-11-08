package dev.nova.deck.bridge;

import android.os.SystemClock;
import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;
import java.util.concurrent.TimeUnit;

/** Uses the same Settings keys and commands as the Nova's native Quick Settings tiles. */
final class ShellDevice {
    static final String CLOSE_SCREEN_WHEN_OUTPUT_VIDEO="close_screen_when_output_video";
    static final String CONTROLLER_MOUSE="global_gamepad_to_mouse_mode";
    static final String LED_BRIGHTNESS="led_light_brightness_percent";
    static final String LED_RESTORE_BRIGHTNESS="duodash_led_restore_brightness";
    private static final String[] PANEL_BACKLIGHT_PATHS={"/sys/class/backlight/panel0-backlight","/sys/class/hdmi/backlight/lcdbl","/sys/hdmi/lcdbl"};
    private static final String PANEL_BACKLIGHT_ENABLE="/sys/class/gpio5_pwm2/en_backlight";
    private static final String PANEL_LEASE="/data/local/tmp/duodash-panel-power-expiry";
    private static final String PANEL_WATCHER="/data/local/tmp/duodash-panel-power-watcher";
    private final Map<String,String> system=new HashMap<>(),global=new HashMap<>(),secure=new HashMap<>();
    private long sampled;
    private String nativeTiles="";
    private boolean nativeControllerMouse;
    private final List<String> cpuThermals=new ArrayList<>();
    private final CpuLoadSampler cpuLoadSampler=new CpuLoadSampler();
    private static final String[] GPU_LOAD_PERCENT_PATHS={
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/gpu_busy"
    };
    private static final String GPU_BUSY_TIME_PATH="/sys/class/kgsl/kgsl-3d0/gpubusy";
    private final ShellRecorder recorder=new ShellRecorder();
    private final Set<Integer> refreshRates=new TreeSet<>();
    void refreshRates(Set<Integer> rates){refreshRates.clear();refreshRates.addAll(rates);}
    void reset(){recorder.stop();}
    ShellDevice(){
        try(DirectoryStream<Path> paths=Files.newDirectoryStream(Paths.get("/sys/class/input"),"event*")){
            for(Path path:paths)if("Retroid Pocket Virtual Mouse".equals(read(path.resolve("device/name").toString()))){nativeControllerMouse=true;break;}
        }catch(Exception ignored){}
        try{nativeTiles=run("settings","get","secure","sysui_qs_tiles").trim();}catch(Exception ignored){}
        try(DirectoryStream<Path> paths=Files.newDirectoryStream(Paths.get("/sys/class/thermal"),"thermal_zone*")){for(Path path:paths)try{String type=read(path.resolve("type").toString());if(type.startsWith("cpu-")||type.startsWith("cpuss"))cpuThermals.add(path.resolve("temp").toString());}catch(Exception ignored){}}catch(Exception ignored){}
    }
    void sendGamepad(String token,int key,int action,boolean toggle){
        try{run("am","broadcast","--receiver-foreground","-a","dev.nova.deck.GAMEPAD_EVENT","-n","dev.nova.deck/.GamepadShortcutReceiver","--es","token",token,"--ei","key",Integer.toString(key),"--ei","action",Integer.toString(action),"--ez","toggle",Boolean.toString(toggle));}
        catch(Exception failure){android.util.Log.w("NovaDeckBridge","Controller notification failed: "+failure.getClass().getSimpleName());}
    }
    void sendShortcut(String token,int key,String action){
        try{run("am","broadcast","--receiver-foreground","-a","dev.nova.deck.GAMEPAD_EVENT","-n","dev.nova.deck/.GamepadShortcutReceiver","--es","token",token,"--ei","key",Integer.toString(key),"--ei","action",Integer.toString(android.view.KeyEvent.ACTION_DOWN),"--es","shortcut_action",action);}
        catch(Exception failure){android.util.Log.w("NovaDeckBridge","Controller shortcut notification failed: "+failure.getClass().getSimpleName());}
    }
    private void snapshot()throws Exception{
        if(sampled!=0&&SystemClock.elapsedRealtime()-sampled<2500)return;
        load(system,run("settings","list","system"),Set.of(CONTROLLER_MOUSE,"fan_mode","performance_mode","fan_speed","accelerometer_rotation","is_charging_separation","temp_abxy_layout_mode","flip_button_layout","no_create_gamepad_button_layout","trigger_input_mode",LED_BRIGHTNESS,"percent_80_charge_limit","charging_limit_power_limit","peak_refresh_rate",CLOSE_SCREEN_WHEN_OUTPUT_VIDEO));
        load(global,run("settings","list","global"),Set.of("wifi_on","bluetooth_on","zen_mode","airplane_mode_on","low_power"));
        load(secure,run("settings","list","secure"),Set.of("night_display_activated","accessibility_display_inversion_enabled","location_mode","ui_night_mode"));sampled=SystemClock.elapsedRealtime();
    }
    private static void load(Map<String,String> map,String data,Set<String> allowed){map.clear();for(String line:data.split("\n")){int split=line.indexOf('=');if(split>0&&allowed.contains(line.substring(0,split)))map.put(line.substring(0,split),line.substring(split+1));}}
    private static int value(Map<String,String> map,String key,int fallback){try{return Integer.parseInt(map.get(key));}catch(Exception ignored){return fallback;}}
    boolean panelPowerSupported(){
        return Files.isReadable(Paths.get(PANEL_BACKLIGHT_ENABLE))
            && Files.isReadable(Paths.get(PANEL_BACKLIGHT_PATHS[0],"bl_power"));
    }
    boolean panelBacklightOn(){
        try{
            int enabled=Integer.parseInt(read(PANEL_BACKLIGHT_ENABLE));
            int power=Integer.parseInt(read(Paths.get(PANEL_BACKLIGHT_PATHS[0],"bl_power").toString()));
            return enabled!=0&&power==0;
        }catch(Exception ignored){return false;}
    }
    String closeScreenWhenOutputVideo()throws Exception{
        String value=run("settings","get","system",CLOSE_SCREEN_WHEN_OUTPUT_VIDEO).trim();
        return value.isEmpty()||value.equals("null")?null:value;
    }
    void closeScreenWhenOutputVideo(String value)throws Exception{
        if(value==null)run("settings","delete","system",CLOSE_SCREEN_WHEN_OUTPUT_VIDEO);
        else if(value.equals("0")||value.equals("1"))run("settings","put","system",CLOSE_SCREEN_WHEN_OUTPUT_VIDEO,value);
        else throw new IllegalArgumentException("Unsupported screen-off setting value");
        sampled=0;
    }
    int panelScreenOffValue(){
        try{snapshot();return value(system,CLOSE_SCREEN_WHEN_OUTPUT_VIDEO,-1);}
        catch(Exception ignored){return -1;}
    }
    void armPanelPowerFailsafe()throws Exception{
        long expiry=SystemClock.elapsedRealtime()/1000L+30L;
        String script="mkdir -p /data/local/tmp; printf '%s\\n' '"+expiry+"' > "+PANEL_LEASE+
            "; p=$(cat "+PANEL_WATCHER+" 2>/dev/null); alive=0; if [ -n \"$p\" ] && kill -0 \"$p\" 2>/dev/null && tr '\\000' ' ' < /proc/$p/cmdline 2>/dev/null | grep -q 'duodash-panel-power-expiry'; then alive=1; fi; if [ \"$alive\" = 0 ]; then "
            +"(while [ -f "+PANEL_LEASE+" ]; do e=$(cat "+PANEL_LEASE+" 2>/dev/null); n=$(awk '{print int($1)}' /proc/uptime); "
            +"case \"$e\" in ''|*[!0-9]*) rm -f "+PANEL_LEASE+"; exit;; esac; "
            +"if [ \"$n\" -ge \"$e\" ]; then sleep 1; e2=$(cat "+PANEL_LEASE+" 2>/dev/null); n2=$(awk '{print int($1)}' /proc/uptime); "
            +"if [ \"$e\" = \"$e2\" ] && [ \"$n2\" -ge \"$e2\" ]; then "
            +"if [ \"$(settings get system "+CLOSE_SCREEN_WHEN_OUTPUT_VIDEO+" 2>/dev/null)\" = 1 ]; then settings put system "+CLOSE_SCREEN_WHEN_OUTPUT_VIDEO+" 0; fi; "
            +"rm -f "+PANEL_LEASE+"; exit; fi; fi; sleep 5; done) >/dev/null 2>&1 </dev/null & echo $! > "+PANEL_WATCHER+"; fi";
        run("sh","-c",script);
    }
    void cancelPanelPowerFailsafe()throws Exception{run("rm","-f",PANEL_LEASE);}
    boolean panelPowerFailsafeActive(){
        try{return run("sh","-c","[ -f "+PANEL_LEASE+" ] && echo 1").trim().equals("1");}
        catch(Exception ignored){return false;}
    }
    JSONObject status()throws Exception{
        snapshot();JSONObject result=new JSONObject();
        add(result,"controller_mouse",nativeControllerMouse,value(system,CONTROLLER_MOUSE,0)==1,nativeControllerMouse?(value(system,CONTROLLER_MOUSE,0)==1?"On · left stick moves pointer":"Off"):"Requires supported Retroid firmware");
        add(result,"wifi",true,value(global,"wifi_on",0)==1,"");add(result,"bluetooth",true,value(global,"bluetooth_on",0)==1,"");
        add(result,"rotation",true,value(system,"accelerometer_rotation",0)==1,"");add(result,"dnd",true,value(global,"zen_mode",0)!=0,"");
        add(result,"charging",has("charging"),value(system,"is_charging_separation",0)==1,"Direct power");
        add(result,"charge_limit",has("charge_limit"),value(system,"percent_80_charge_limit",0)==1,"80% battery limit");
        add(result,"airplane",has("airplane"),value(global,"airplane_mode_on",0)==1,"");
        add(result,"battery_saver",has("battery"),value(global,"low_power",0)==1,"");
        add(result,"night",has("night"),value(secure,"night_display_activated",0)==1,"");
        add(result,"dark",has("dark"),value(secure,"ui_night_mode",1)==2,"");
        add(result,"inversion",has("inversion"),value(secure,"accessibility_display_inversion_enabled",0)==1,"");
        add(result,"location",has("location"),value(secure,"location_mode",0)!=0,"");
        add(result,"led",has("ledlights"),doubleValue(system.get("led_light_brightness_percent"),1)>0,"Joystick lights");
        add(result,"screenrecord",has("screenrecord"),recorder.active(),recorder.active()?"Recording · tap to stop":"Video · up to 3 min");
        if(!refreshRates.isEmpty()){String[] labels=new String[refreshRates.size()];int[] values=new int[refreshRates.size()];int i=0;for(int rate:refreshRates){labels[i]=rate+" Hz";values[i++]=rate;}mode(result,"refresh_mode",has("peak_refresh_rate"), (int)doubleValue(system.get("peak_refresh_rate"),60),labels,values);}
        mode(result,"fan",has("fan"),value(system,"fan_mode",4),dev.nova.deck.NativeModes.fanLabels(),dev.nova.deck.NativeModes.fanValues());
        mode(result,"performance",has("performancemode"),value(system,"performance_mode",0),new String[]{"Normal","Standard","High"},new int[]{0,1,2});
        mode(result,"controller_style",has("controllerStyle"),value(system,"temp_abxy_layout_mode",1),new String[]{"Retro","Xbox","Disabled"},new int[]{0,1,2});
        mode(result,"trigger_mode",has("triggerInputMode"),value(system,"trigger_input_mode",2),new String[]{"Analog","Digital","Both"},new int[]{0,1,2});
        nativeTile(result,"keep_awake","com.rp.settings/com.ro.settings.service.KeepScreenOnTileService");
        nativeTile(result,"force_landscape","com.rp.settings/com.ro.settings.service.ForceLandscapeTileService");
        nativeTile(result,"game_assistant","com.rp.gameassistant/com.ro.gameassistant.service.FloatingWindowTileService");
        return result;
    }
    private boolean has(String spec){return Arrays.asList(nativeTiles.split(",")).contains(spec);}
    private void nativeTile(JSONObject target,String id,String component)throws JSONException{add(target,id,nativeTiles.contains("custom("+component+")"),false,"Native Quick Setting");}
    private void add(JSONObject target,String id,boolean available,boolean active,String detail)throws JSONException{target.put(id,new JSONObject().put("available",available).put("active",active).put("detail",detail));}
    private void mode(JSONObject target,String id,boolean available,int current,String[] labels,int[] values)throws JSONException{JSONArray options=new JSONArray();String label="";for(int i=0;i<values.length;i++){options.put(new JSONObject().put("label",labels[i]).put("value",values[i]));if(values[i]==current)label=labels[i];}target.put(id,new JSONObject().put("available",available).put("active",true).put("detail",label).put("value",current).put("options",options));}
    void control(String id,boolean enabled)throws Exception{
        switch(id){
            case "controller_mouse":if(!nativeControllerMouse)throw new IllegalStateException("Controller mouse is unavailable on this firmware");put("system",CONTROLLER_MOUSE,enabled?1:0);break;
            case "wifi":run("cmd","wifi","set-wifi-enabled",enabled?"enabled":"disabled");break;
            case "bluetooth":run("cmd","bluetooth_manager",enabled?"enable":"disable");break;
            case "rotation":put("system","accelerometer_rotation",enabled?1:0);break;
            case "dnd":run("cmd","notification","set_dnd",enabled?"priority":"all");break;
            case "charging":put("system","is_charging_separation",enabled?1:0);break;
            case "charge_limit":put("system","percent_80_charge_limit",enabled?1:0);break;
            case "led":setLedBrightness(enabled);break;
            case "airplane":run("cmd","connectivity","airplane-mode",enabled?"enable":"disable");break;
            case "battery_saver":run("cmd","power","set-mode",enabled?"1":"0");break;
            case "night":put("secure","night_display_activated",enabled?1:0);break;
            case "dark":run("cmd","uimode","night",enabled?"yes":"no");break;
            case "inversion":put("secure","accessibility_display_inversion_enabled",enabled?1:0);break;
            case "location":run("cmd","location","set-location-enabled",Boolean.toString(enabled),"--user","0");break;
            case "screenrecord":if(enabled)recorder.start();else recorder.stop();break;
            case "keep_awake":run("cmd","statusbar","click-tile","com.rp.settings/com.ro.settings.service.KeepScreenOnTileService");break;
            case "force_landscape":run("cmd","statusbar","click-tile","com.rp.settings/com.ro.settings.service.ForceLandscapeTileService");break;
            case "game_assistant":run("cmd","statusbar","click-tile","com.rp.gameassistant/com.ro.gameassistant.service.FloatingWindowTileService");break;
            default:throw new IllegalArgumentException("Unknown Android control");
        }sampled=0;
    }
    private void setLedBrightness(boolean enabled)throws Exception{
        String current=run("settings","get","system",LED_BRIGHTNESS).trim();
        if(enabled){
            if(!LedBrightnessPolicy.isUsableBrightness(current)){
                String saved=run("settings","get","system",LED_RESTORE_BRIGHTNESS).trim();
                String restore=LedBrightnessPolicy.valueToRestore(current,saved);
                run("settings","put","system",LED_BRIGHTNESS,restore);
            }
            return;
        }
        String capture=LedBrightnessPolicy.brightnessToCaptureWhenTurningOff(current);
        if(capture!=null){
            // Save and verify before turning the native LEDs off, so a failed save never loses the level.
            run("settings","put","system",LED_RESTORE_BRIGHTNESS,capture);
            String saved=run("settings","get","system",LED_RESTORE_BRIGHTNESS).trim();
            if(!capture.equals(saved))throw new IOException("Could not preserve joystick light brightness");
        }
        // If already off, leave the last valid restore value untouched.
        run("settings","put","system",LED_BRIGHTNESS,"0.0");
    }
    void mode(String id,int mode)throws Exception{
        if(id.equals("fan")){if(!dev.nova.deck.NativeModes.validFan(mode))throw new IllegalArgumentException("Unsupported Nova fan mode");put("system","fan_mode",mode);sampled=0;return;}
        if(id.equals("refresh_mode")){if(!refreshRates.contains(mode))throw new IllegalArgumentException("Unsupported refresh rate");put("system","peak_refresh_rate",mode);put("system","min_refresh_rate",mode);sampled=0;return;}
        String key;int max;
        switch(id){case "fan":key="fan_mode";max=4;break;case "performance":key="performance_mode";max=2;break;case "trigger_mode":key="trigger_input_mode";max=2;break;case "controller_style":key="temp_abxy_layout_mode";max=2;break;default:throw new IllegalArgumentException("Unknown native setting");}
        if(mode<0||mode>max)throw new IllegalArgumentException("Invalid native option");
        put("system",key,mode);
        if(id.equals("performance"))run("setprop","persist.vendor.debug.mode",Integer.toString(mode));
        if(id.equals("controller_style")){put("system","flip_button_layout",mode==0?1:0);put("system","no_create_gamepad_button_layout",mode==2?1:0);}
        sampled=0;
    }
    private void put(String namespace,String key,int value)throws Exception{run("settings","put",namespace,key,Integer.toString(value));}
    String run(String...args)throws Exception{
        java.lang.Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();Thread drain=new Thread(()->{try{byte[] buffer=new byte[4096];for(int n;(n=process.getInputStream().read(buffer))>=0;){if(bytes.size()<262144)bytes.write(buffer,0,n);}}catch(IOException ignored){}},"nova-command-output");drain.setDaemon(true);drain.start();
        if(!process.waitFor(4,TimeUnit.SECONDS)){process.destroy();throw new IOException("Android control timed out");}drain.join(500);
        if(process.exitValue()!=0)throw new IOException("Android refused this control");return bytes.toString(StandardCharsets.UTF_8.name());
    }
    String windowDisplayFocusDump()throws Exception{return run("dumpsys","window","displays");}
    private static String read(String file)throws IOException{return new String(Files.readAllBytes(Paths.get(file)),StandardCharsets.UTF_8).trim();}
    private static double number(String file){try{return Double.parseDouble(read(file));}catch(Exception ignored){return Double.NaN;}}
    private static double doubleValue(String value,double fallback){try{return Double.parseDouble(value);}catch(Exception ignored){return fallback;}}
    JSONObject telemetry()throws JSONException{
        JSONObject result=new JSONObject();double cpu=Double.NaN;
        for(int i=0;i<8;i++){double value=number("/sys/devices/system/cpu/cpu"+i+"/cpufreq/scaling_cur_freq");if(Double.isFinite(value)&&value>0)cpu=Double.isNaN(cpu)?value:Math.max(cpu,value);}
        double gpu=number("/sys/class/kgsl/kgsl-3d0/gpuclk"),temperature=Double.NaN;
        CpuLoadSampler.Reading cpuLoad=cpuLoadSampler.sample(readOptional("/proc/stat"));
        GpuUsageParser.Reading gpuLoad=gpuUsage();
        for(String path:cpuThermals){double value=number(path)/1000d;if(Double.isFinite(value)&&value>0&&value<130)temperature=Double.isNaN(temperature)?value:Math.max(temperature,value);}
        double duty=number("/sys/class/gpio5_pwm2/duty"),period=number("/sys/class/gpio5_pwm2/period"),rpm=number("/sys/class/gpio5_pwm2/speed");
        if(Double.isFinite(cpu))result.put("cpuGhz",cpu/1000000d);if(Double.isFinite(gpu)&&gpu>0)result.put("gpuMhz",gpu/1000000d);
        if(cpuLoad.available())result.put("cpuLoadPercent",cpuLoad.percent());else result.put("cpuLoadReason",cpuLoad.reason());
        if(gpuLoad.available())result.put("gpuLoadPercent",gpuLoad.percent());else result.put("gpuLoadReason",gpuLoad.reason());
        if(Double.isFinite(temperature))result.put("cpuTemperature",temperature);
        if(Double.isFinite(duty)&&period>0)result.put("fanPercent",Math.max(0,Math.min(100,duty*100/period)));
        if(Double.isFinite(rpm))result.put("fanRpm",rpm);return result;
    }

    private static String readOptional(String path){try{return read(path);}catch(Exception ignored){return null;}}
    private static GpuUsageParser.Reading gpuUsage(){
        boolean present=false;
        for(String path:GPU_LOAD_PERCENT_PATHS){
            Path candidate=Paths.get(path);if(!Files.exists(candidate))continue;present=true;
            try{if(Files.isReadable(candidate)){GpuUsageParser.Reading reading=GpuUsageParser.parsePercentage(read(path));if(reading.available())return reading;}}
            catch(Exception ignored){}
        }
        Path busyTime=Paths.get(GPU_BUSY_TIME_PATH);
        if(Files.exists(busyTime)){
            present=true;
            try{if(Files.isReadable(busyTime)){GpuUsageParser.Reading reading=GpuUsageParser.parseBusyTime(read(GPU_BUSY_TIME_PATH));if(reading.available())return reading;}}
            catch(Exception ignored){}
        }
        return GpuUsageParser.unavailable(present?"GPU usage counters unreadable or malformed":"GPU usage unavailable on this driver");
    }
}
