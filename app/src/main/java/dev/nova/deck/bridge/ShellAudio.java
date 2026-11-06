package dev.nova.deck.bridge;

import android.os.IBinder;
import dev.nova.deck.ControlLogic;
import org.json.*;
import java.util.*;

/** Per-player multipliers for foreground apps, never a substitute master-volume control. */
final class ShellAudio {
    private Object audio,packages;
    private Class<?> audioInterface,packageInterface;
    private final Map<Integer,Object> controlled=new HashMap<>();
    private final Map<Integer,Integer> applied=new HashMap<>();
    private final Map<String,Integer> uids=new HashMap<>();
    private int mainGain=100,upperGain=100;
    private String unavailable="Audio controls are not connected";
    private final boolean apiAvailable;
    private final AudioStatusRecovery recovery;
    ShellAudio(){
        boolean available;
        try{
            Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"audio");
            audioInterface=Class.forName("android.media.IAudioService");
            audio=Class.forName("android.media.IAudioService$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
            packages=Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
            packageInterface=Class.forName("android.content.pm.IPackageManager");
            available=((Number)packageInterface.getMethod("checkUidPermission",String.class,int.class).invoke(packages,"android.permission.MODIFY_AUDIO_ROUTING",2000)).intValue()==0;
            if(!available)unavailable="This firmware does not expose app-audio control";
        }catch(Exception failure){unavailable="App-audio API is unavailable on this firmware";available=false;}
        apiAvailable=available;
        recovery=new AudioStatusRecovery(apiAvailable);
    }
    void gains(int main,int upper){mainGain=main;upperGain=upper;}
    boolean needsRefresh(){return mainGain!=100||upperGain!=100||!controlled.isEmpty();}
    private int uid(String pkg){
        if(pkg==null||pkg.isEmpty())return -1;
        Integer old=uids.get(pkg);if(old!=null)return old;
        try{int uid=((Number)packageInterface.getMethod("getPackageUid",String.class,long.class,int.class).invoke(packages,pkg,0L,0)).intValue();if(uid>=0)uids.put(pkg,uid);return uid;}catch(Exception failure){return -1;}
    }
    JSONObject status(List<ControlLogic.Task> tasks,int external)throws Exception{
        ControlLogic.Task main=ControlLogic.foreground(tasks,0),upper=ControlLogic.foreground(tasks,external);
        int mainUid=uid(main==null?"":main.pkg),upperUid=uid(upper==null?"":upper.pkg);
        boolean shared=mainUid>=0&&mainUid==upperUid,mainActive=false,upperActive=false;
        boolean queryHealthy=true;
        if(apiAvailable){
            try{
                List<?> players=(List<?>)audioInterface.getMethod("getActivePlaybackConfigurations").invoke(audio);
                Set<Integer> seen=new HashSet<>();
                for(Object player:players){
                    int id=-1;
                    try{
                        Class<?> type=player.getClass();int playerUid=((Number)type.getMethod("getClientUid").invoke(player)).intValue();
                        id=((Number)type.getMethod("getPlayerInterfaceId").invoke(player)).intValue();
                        Object proxy=type.getMethod("getPlayerProxy").invoke(player);if(proxy==null||playerUid<0)continue;
                        seen.add(id);int desired=playerUid==mainUid?mainGain:playerUid==upperUid&&!shared?upperGain:100;
                        if(playerUid==mainUid)mainActive=true;if(playerUid==upperUid)upperActive=true;
                        if(desired!=100||controlled.containsKey(id)){
                            if(!Objects.equals(applied.get(id),desired))proxy.getClass().getMethod("setVolume",float.class).invoke(proxy,desired/100f);
                            if(desired==100){controlled.remove(id);applied.remove(id);}else{controlled.put(id,proxy);applied.put(id,desired);}
                        }
                    }catch(Exception stalePlayer){
                        // A player can disappear between enumeration and proxy call. Retire only that player.
                        Object old=controlled.remove(id);applied.remove(id);
                        if(old!=null)try{old.getClass().getMethod("setVolume",float.class).invoke(old,1f);}catch(Exception ignored){}
                    }
                }
                controlled.keySet().retainAll(seen);applied.keySet().retainAll(seen);
            }catch(Exception failure){unavailable="App-audio player information is temporarily unavailable";queryHealthy=false;}
        }
        recovery.recordQuery(queryHealthy);
        boolean supported=recovery.available();
        return new JSONObject().put("supported",supported).put("mainAvailable",supported&&mainUid>=0)
            .put("upperAvailable",supported&&upperUid>=0&&!shared).put("mainGain",mainGain).put("upperGain",upperGain)
            .put("mainReason",!supported?unavailable:mainUid<0?"Open an app on the main screen":mainActive?"App audio":"Applies when this app plays audio")
            .put("upperReason",!supported?unavailable:shared?"One app shares a single audio stream":upperUid<0?"Open an app on the upper screen":upperActive?"App audio":"Applies when this app plays audio");
    }
    void reset(){for(Object proxy:controlled.values())try{proxy.getClass().getMethod("setVolume",float.class).invoke(proxy,1f);}catch(Exception ignored){}controlled.clear();applied.clear();}
}
