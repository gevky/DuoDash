package dev.nova.deck.bridge;
import dev.nova.deck.*;
import android.view.KeyEvent;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only internal-controller listener, independent of game/emulator event consumption. */
final class GamepadMonitor {
    private final ShellDevice device;private final String token;
    private volatile java.lang.Process process;private volatile boolean running=true,recording,available;
    private ShortcutMatcher matcher=new ShortcutMatcher("");
    private final Map<Integer,Integer> layout=new HashMap<>();
    GamepadMonitor(ShellDevice device,String token){this.device=device;this.token=token;Thread thread=new Thread(this::listen,"nova-gamepad");thread.setDaemon(true);thread.start();}
    synchronized void configure(String keys){matcher=new ShortcutMatcher(keys);}
    void record(boolean value){recording=value;}
    boolean available(){return available;}
    private void listen(){
        try{
            for(String line:Files.readAllLines(Paths.get("/system/usr/keylayout/Generic.kl"))){String[] parts=line.trim().split("\\s+");if(parts.length>=3&&parts[0].equals("key"))try{int scan=Integer.parseInt(parts[1]),key=KeyEvent.keyCodeFromString("KEYCODE_"+parts[2]);if(key>0)layout.put(scan,key);}catch(NumberFormatException ignored){}}
            Path controller=null;try(DirectoryStream<Path> paths=Files.newDirectoryStream(Paths.get("/sys/class/input"),"event*")){for(Path path:paths)try{String name=new String(Files.readAllBytes(path.resolve("device/name")),StandardCharsets.UTF_8).trim();if(name.equals("Retroid Pocket Controller")){controller=path;break;}}catch(IOException ignored){}}
            if(controller==null)return;
            process=new ProcessBuilder("getevent","-t","/dev/input/"+controller.getFileName()).redirectErrorStream(true).start();
            available=true;
            try(BufferedReader input=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){
                for(String line;running&&(line=input.readLine())!=null;){ControllerKeyParser.Key key=ControllerKeyParser.parse(line,layout);if(key==null||key.code==KeyEvent.KEYCODE_HOME||key.code==KeyEvent.KEYCODE_POWER)continue;
                    if(recording){device.sendGamepad(token,key.code,key.down?KeyEvent.ACTION_DOWN:KeyEvent.ACTION_UP,false);continue;}
                    boolean toggle; synchronized(this){toggle=matcher.event(key.code,key.down,key.repeat).toggle;}
                    if(toggle)device.sendGamepad(token,key.code,KeyEvent.ACTION_DOWN,true);
                }
            }
        }catch(Exception failure){android.util.Log.w("NovaDeckBridge","Internal gamepad listener unavailable: "+failure.getClass().getSimpleName());}
        finally{available=false;if(process!=null)process.destroy();}
    }
    void close(){running=false;if(process!=null)process.destroy();}
}
