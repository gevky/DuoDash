package dev.nova.deck;
import java.util.*;

/** Snapshot rendered on the UI thread. Percentages are 0..100. */
public final class UiState {
    public String layoutKey = "handheld";
    public String profile = "New output";
    public String deviceName = "Retroid add-on";
    public String connection = "Connecting system controls";
    public String mainApp = "Home";
    public String upperApp = "No app";
    public String feedback = "Changes save automatically";
    public boolean bridgeReady;
    public boolean externalConnected;
    public boolean showDuoDashOnExternal;
    public String displayMode=DisplayModePolicy.DUAL;
    public boolean busy;
    public boolean fullScreen=true;
    public boolean horizontalSliders=true;
    public boolean compactControls=true;
    public int actionColumns=4;
    public boolean autoProfile = true;
    public int main = 80;
    public int upper = 100;
    public int volume = 50;
    public boolean volumeMuted,mainAudioMuted,upperAudioMuted;
    public int battery = -1;
    public boolean batteryPowered,batteryCharging,batteryFull,showBatteryTime=true;
    public boolean showHeaderHz=true,showHeaderWifi=true,showHeaderClock=true,wifiConnected;
    public int batteryRuntimeMinutes=-1;
    public float refreshHz = 60;
    public float temperatureC = Float.NaN;
    public float powerW = Float.NaN;
    public float usedMemoryGb = Float.NaN;
    public float totalMemoryGb = Float.NaN;
    public float cpuGhz=Float.NaN,gpuMhz=Float.NaN,fanPercent=Float.NaN;
    public float cpuLoadPercent=Float.NaN,gpuLoadPercent=Float.NaN;
    public String cpuLoadReason="",gpuLoadReason="";
    public String cpuMetricMode=MetricModePolicy.USAGE,gpuMetricMode=MetricModePolicy.USAGE;
    public float cpuTemperatureC=Float.NaN;
    public float mainFps=Float.NaN,upperFps=Float.NaN;
    public String fpsScreen="main";
    public String mainFpsReason="Waiting for app frames",upperFpsReason="Waiting for app frames";
    public boolean mainBlack,upperBlack;
    public boolean useExternalApps,screenOffRequested,panelPowerSupported;
    public String temperatureLabel="Battery temp";
    public int mainAudio=100,upperAudio=100;
    public boolean mainAudioAvailable,upperAudioAvailable,perAppAudioAvailable;
    public String mainAudioReason="No active audio",upperAudioReason="No active audio";
    public String wideControls="";
    public String smallControls="";
    public String controlSizes="";
    public final Map<String,ControlStatus> controls=new LinkedHashMap<>();
    public final List<AppShortcut> pinnedApps=new ArrayList<>();
    public final List<DisplayCanvas> displayCanvases=new ArrayList<>();
    public static final class ControlStatus {
        public boolean available=true,active;
        public String detail="";
        public int value;
        public final List<String> optionLabels=new ArrayList<>();
        public final List<Integer> optionValues=new ArrayList<>();
        public ControlStatus(){}
        public ControlStatus(boolean available,boolean active,String detail){this.available=available;this.active=active;this.detail=detail;}
    }
    public static final class AppShortcut {
        public String id,label,component;public boolean upper;
        public AppShortcut(String id,String label,String component,boolean upper){this.id=id;this.label=label;this.component=component;this.upper=upper;}
    }
    public static final class DisplayCanvas {
        public int id,rotation,currentWidth,currentHeight,nativeWidth,nativeHeight;
        public String identity,label,savedOptionId;
        public boolean mirroredPrimary;
        public final List<CanvasOption> options=new ArrayList<>();
    }
    public static final class CanvasOption {
        public String id,label;public int width,height;
        public CanvasOption(String id,String label,int width,int height){this.id=id;this.label=label;this.width=width;this.height=height;}
    }
    public static final class ResolutionPreview {
        public boolean ok,mirroredPrimary;public String transactionId,message,optionId,displayIdentity;public int displayId,width,height;
        public ResolutionPreview(boolean ok,String transactionId,String message,String optionId,String displayIdentity,int displayId,int width,int height){this.ok=ok;this.transactionId=transactionId;this.message=message;this.optionId=optionId;this.displayIdentity=displayIdentity;this.displayId=displayId;this.width=width;this.height=height;}
    }
    public String controlOrder = ControlCatalog.defaultOrder();
    public String hiddenControls = ControlCatalog.defaultHidden();
}
