package dev.nova.deck.bridge;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** User-started screen video only; fixed output folder and a three-minute limit. */
final class ShellRecorder {
    private java.lang.Process wrapper;private int pid;private String output="";
    synchronized boolean active(){return wrapper!=null&&wrapper.isAlive();}
    synchronized void start()throws Exception{
        if(active())return;Path folder=Paths.get("/sdcard/Movies/DuoDash");Files.createDirectories(folder);output=folder.resolve("DuoDash-"+System.currentTimeMillis()+".mp4").toString();
        wrapper=new ProcessBuilder("sh","-c","screenrecord --time-limit 180 \"$1\" > /data/local/tmp/nova-deck-recording.log 2>&1 & child=$!; echo \"$child\"; wait \"$child\"","nova-record",output).redirectErrorStream(true).start();
        String value=new BufferedReader(new InputStreamReader(wrapper.getInputStream())).readLine();
        try{pid=Integer.parseInt(value);}catch(Exception invalid){wrapper.destroy();wrapper=null;throw new IOException("Android could not start recording");}
        if(wrapper.waitFor(250,TimeUnit.MILLISECONDS)){wrapper=null;throw new IOException("Android could not start recording");}
    }
    synchronized void stop(){
        if(!active()){wrapper=null;return;}
        try{java.lang.Process signal=new ProcessBuilder("kill","-2",Integer.toString(pid)).start();signal.waitFor(1,TimeUnit.SECONDS);if(!wrapper.waitFor(3,TimeUnit.SECONDS))wrapper.destroy();
            new ProcessBuilder("am","broadcast","-a","android.intent.action.MEDIA_SCANNER_SCAN_FILE","-d","file://"+output).redirectOutput(new File("/dev/null")).redirectError(new File("/dev/null")).start();
        }catch(Exception failure){wrapper.destroy();}finally{wrapper=null;}
    }
}
