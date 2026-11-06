package dev.nova.deck.bridge;
import dev.nova.deck.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Read-only samples of each foreground app's own SurfaceView presentation history. */
final class ShellFps {
    private long sampled;private String signature="";private JSONObject cached=new JSONObject();
    JSONObject status(List<ControlLogic.Task> tasks,int external)throws Exception{
        ControlLogic.Task main=ControlLogic.foreground(tasks,0),upper=ControlLogic.foreground(tasks,external);
        String key=(main==null?"":main.pkg)+"|"+(upper==null?"":upper.pkg);
        long now=android.os.SystemClock.elapsedRealtime();if(key.equals(signature)&&now-sampled<1200)return cached;
        JSONObject result=new JSONObject();
        if(main!=null&&upper!=null&&main.pkg.equals(upper.pkg)){result.put("main",missing("One app spans both screens; frame surface is ambiguous")).put("upper",missing("One app spans both screens; frame surface is ambiguous"));}
        else try{List<String> layers=Arrays.asList(run("dumpsys","SurfaceFlinger","--list").split("\n"));result.put("main",sample(main,layers)).put("upper",sample(upper,layers));}
        catch(Exception unavailable){result.put("main",missing("App frame statistics unavailable")).put("upper",missing("App frame statistics unavailable"));}
        sampled=now;signature=key;cached=result;return result;
    }
    private JSONObject missing(String reason)throws JSONException{return new JSONObject().put("reason",reason);}
    private JSONObject sample(ControlLogic.Task task,List<String> layers)throws Exception{
        if(task==null)return missing("Open an app on this screen");
        String layer=SurfaceLayerSelector.select(task.pkg,layers);
        if(layer==null)return missing("No app presentation layer frame data");
        // Prefer BLAST SurfaceViews. Some renderers submit directly to their activity window
        // layer, which still has SurfaceFlinger presentation timestamps but no SurfaceView child.
        float fps=FrameRate.fromLatency(run("dumpsys","SurfaceFlinger","--latency",layer),System.nanoTime());
        JSONObject result=missing(Float.isNaN(fps)?"Waiting for presented app frames":fps==0?"No recent app frames":"App presentations, not internal emulation FPS");
        if(!Float.isNaN(fps))result.put("fps",fps);return result.put("package",task.pkg);
    }
    private String run(String...args)throws Exception{
        Process process=new ProcessBuilder(args).redirectErrorStream(true).start();ByteArrayOutputStream output=new ByteArrayOutputStream();
        Thread drain=new Thread(()->{try{byte[] buffer=new byte[4096];for(int n;(n=process.getInputStream().read(buffer))>=0;)if(output.size()<131072)output.write(buffer,0,n);}catch(IOException ignored){}},"nova-frame-stats");drain.setDaemon(true);drain.start();
        if(!process.waitFor(900,TimeUnit.MILLISECONDS)){process.destroy();throw new IOException("App frame statistics timed out");}drain.join(100);
        if(process.exitValue()!=0)throw new IOException("App frame statistics unavailable");return output.toString(StandardCharsets.UTF_8.name());
    }
}
