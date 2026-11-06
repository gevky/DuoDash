package dev.nova.deck;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Captures selected public displays, then saves PNGs without leaving DuoDash hidden. */
final class ScreenshotCapture {
    interface Listener { void finished(String message); }

    private static final long FIRST_CAPTURE_DELAY_MS=100;
    private static final long CAPTURE_GAP_MS=450;
    private static final long CAPTURE_TIMEOUT_MS=12_000;

    private final Context context;
    private final DeckAccessibilityService accessibility;
    private final String target;
    private final boolean includeDuoDash;
    private final List<View> surfaces;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{
        Thread thread=new Thread(r,"DuoDashScreenshotWriter");thread.setDaemon(true);return thread;
    });
    private final List<SurfaceState> surfaceStates=new ArrayList<>();
    private final List<OverlayImage> overlayImages=new ArrayList<>();
    private final List<CaptureResult> results=new ArrayList<>();
    private final Set<Uri> pendingUris=new HashSet<>();
    private final Object uriLock=new Object();
    private int[] displayIds;
    private int nextDisplay;
    private int activeRequest;
    private boolean awaitingCapture,viewsHidden,capturesFinished,writingStarted;
    private volatile boolean cancelled,finished;
    private Runnable scheduledCapture;
    private Runnable timeout;

    ScreenshotCapture(Context context,DeckAccessibilityService accessibility,String target,
                      boolean includeDuoDash,List<View> surfaces,Listener listener){
        this.context=context.getApplicationContext();
        this.accessibility=accessibility;
        this.target=ScreenshotTargetPolicy.normalize(target);
        this.includeDuoDash=includeDuoDash;
        this.surfaces=new ArrayList<>(surfaces);
        this.listener=listener;
    }

    void start(){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Screenshot capture must start on the main thread");
        if(accessibility==null){finishWithMessage("Enable always-visible controls to take screenshots");return;}
        DisplayManager manager=(DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
        int external=findExternalDisplayId(manager);
        try{displayIds=ScreenshotTargetPolicy.selectDisplayIds(target,external);}
        catch(IllegalStateException missing){finishWithMessage("Connect an external display to capture it");return;}
        try{
            if(includeDuoDash)captureOverlayImages(manager);
            hideOwnSurfaces();
            scheduleCapture(FIRST_CAPTURE_DELAY_MS);
        }catch(RuntimeException|OutOfMemoryError failure){
            restoreOwnSurfaces();recycleOverlayImages();
            finishWithMessage("DuoDash could not prepare the screenshot");
        }
    }

    void cancel(){
        if(finished||cancelled)return;
        cancelled=true;
        if(timeout!=null)main.removeCallbacks(timeout);
        if(scheduledCapture!=null)main.removeCallbacks(scheduledCapture);
        restoreOwnSurfaces();recycleOverlayImages();
        if(!writingStarted)recycleCaptureResults();
        writer.shutdown();
        List<Uri> toDelete;
        synchronized(uriLock){toDelete=new ArrayList<>(pendingUris);pendingUris.clear();}
        for(Uri uri:toDelete)try{context.getContentResolver().delete(uri,null,null);}catch(RuntimeException ignored){}
    }

    private int findExternalDisplayId(DisplayManager manager){
        if(manager==null)return -1;
        Display[] displays=manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        for(Display display:displays){
            if(display==null||display.getDisplayId()==0||display.getState()==Display.STATE_OFF)continue;
            int flags=display.getFlags();
            if((flags&Display.FLAG_PRIVATE)!=0||(flags&Display.FLAG_PRESENTATION)==0)continue;
            return display.getDisplayId();
        }
        return -1;
    }

    private void captureOverlayImages(DisplayManager manager){
        Set<Integer> selected=new HashSet<>();for(int id:displayIds)selected.add(id);
        for(View view:surfaces){
            if(view==null||!view.isAttachedToWindow()||view.getVisibility()!=View.VISIBLE||!view.isShown())continue;
            Display display=view.getDisplay();
            if(display==null||!selected.contains(display.getDisplayId()))continue;
            int width=view.getWidth(),height=view.getHeight();
            if(width<=0||height<=0)continue;
            Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            try{
                Canvas canvas=new Canvas(bitmap);
                float alpha=view.getAlpha();
                if(alpha<1f){int layer=canvas.saveLayerAlpha(0,0,width,height,Math.round(alpha*255));view.draw(canvas);canvas.restoreToCount(layer);}
                else view.draw(canvas);
                int[] position=new int[2];view.getLocationOnScreen(position);
                overlayImages.add(new OverlayImage(display.getDisplayId(),position[0],position[1],width,height,bitmap));
            }catch(RuntimeException|OutOfMemoryError failure){bitmap.recycle();throw failure;}
        }
    }

    private void hideOwnSurfaces(){
        viewsHidden=true;
        for(View view:surfaces){
            if(view==null||!view.isAttachedToWindow())continue;
            int visibility=view.getVisibility();surfaceStates.add(new SurfaceState(view,visibility));
            if(visibility==View.VISIBLE)view.setVisibility(View.INVISIBLE);
        }
    }

    private void restoreOwnSurfaces(){
        if(!viewsHidden)return;
        for(SurfaceState state:surfaceStates){
            try{if(state.view.isAttachedToWindow())state.view.setVisibility(state.visibility);}catch(RuntimeException ignored){}
        }
        viewsHidden=false;surfaceStates.clear();
    }

    private void captureNext(){
        if(cancelled||finished)return;
        if(nextDisplay>=displayIds.length){finishCaptures();return;}
        final int displayId=displayIds[nextDisplay];
        final int request=++activeRequest;awaitingCapture=true;
        timeout=()->{
            if(cancelled||!awaitingCapture||request!=activeRequest)return;
            awaitingCapture=false;addFailure(displayId,"timed out");advance();
        };
        main.postDelayed(timeout,CAPTURE_TIMEOUT_MS);
        try{
            accessibility.takeScreenshot(displayId,context.getMainExecutor(),new android.accessibilityservice.AccessibilityService.TakeScreenshotCallback(){
                @Override public void onSuccess(android.accessibilityservice.AccessibilityService.ScreenshotResult result){
                    if(cancelled||!awaitingCapture||request!=activeRequest){closeBuffer(result);return;}
                    main.removeCallbacks(timeout);awaitingCapture=false;
                    Bitmap captured=null;
                    try{captured=copyAndComposite(result,displayId);results.add(new CaptureResult(displayId,captured,null));captured=null;}
                    catch(RuntimeException|OutOfMemoryError failure){addFailure(displayId,"capture failed");}
                    finally{if(captured!=null&&!captured.isRecycled())captured.recycle();advance();}
                }
                @Override public void onFailure(int errorCode){
                    if(cancelled||!awaitingCapture||request!=activeRequest)return;
                    main.removeCallbacks(timeout);awaitingCapture=false;addFailure(displayId,failureReason(displayId,errorCode));advance();
                }
            });
        }catch(RuntimeException failure){
            main.removeCallbacks(timeout);awaitingCapture=false;addFailure(displayId,"capture could not start");advance();
        }
    }

    private Bitmap copyAndComposite(android.accessibilityservice.AccessibilityService.ScreenshotResult result,int displayId){
        HardwareBuffer buffer=result==null?null:result.getHardwareBuffer();
        Bitmap wrapped=null;
        Bitmap output=null;
        try{
            if(buffer==null)throw new IllegalStateException("Missing screenshot buffer");
            ColorSpace colorSpace=result.getColorSpace();
            wrapped=Bitmap.wrapHardwareBuffer(buffer,colorSpace);
            if(wrapped==null)throw new IllegalStateException("Could not read screenshot buffer");
            output=wrapped.copy(Bitmap.Config.ARGB_8888,true);
            if(output==null)throw new IllegalStateException("Could not copy screenshot buffer");
            Canvas canvas=new Canvas(output);
            DisplayManager manager=(DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
            Display display=manager==null?null:manager.getDisplay(displayId);
            DisplayMetrics metrics=new DisplayMetrics();
            if(display!=null)display.getRealMetrics(metrics);
            float scaleX=metrics.widthPixels>0?output.getWidth()/(float)metrics.widthPixels:1f;
            float scaleY=metrics.heightPixels>0?output.getHeight()/(float)metrics.heightPixels:1f;
            for(OverlayImage image:overlayImages){
                if(image.displayId!=displayId||image.bitmap.isRecycled())continue;
                RectF destination=new RectF(image.x*scaleX,image.y*scaleY,(image.x+image.width)*scaleX,(image.y+image.height)*scaleY);
                canvas.drawBitmap(image.bitmap,null,destination,null);
            }
            return output;
        }catch(RuntimeException|OutOfMemoryError failure){
            if(output!=null&&!output.isRecycled())output.recycle();
            throw failure;
        }finally{
            if(wrapped!=null)wrapped.recycle();
            if(buffer!=null)buffer.close();
        }
    }

    private static void closeBuffer(android.accessibilityservice.AccessibilityService.ScreenshotResult result){
        if(result==null)return;
        HardwareBuffer buffer=result.getHardwareBuffer();if(buffer!=null)try{buffer.close();}catch(RuntimeException ignored){}
    }

    private void addFailure(int displayId,String reason){results.add(new CaptureResult(displayId,null,reason));}

    private String failureReason(int displayId,int errorCode){
        switch(errorCode){
            case android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT:return "wait a moment before trying again";
            case android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY:return "display disconnected during capture";
            case android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW:return "protected content blocked capture";
            case android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS:return "accessibility screenshot permission is unavailable";
            default:return label(displayId).toLowerCase(Locale.ROOT)+" capture failed";
        }
    }

    private void advance(){
        if(cancelled)return;
        nextDisplay++;
        if(nextDisplay<displayIds.length)scheduleCapture(CAPTURE_GAP_MS);
        else finishCaptures();
    }

    private void scheduleCapture(long delayMs){
        Runnable task=new Runnable(){@Override public void run(){if(scheduledCapture==this)scheduledCapture=null;captureNext();}};
        scheduledCapture=task;main.postDelayed(task,delayMs);
    }

    private void finishCaptures(){
        if(capturesFinished||cancelled)return;
        capturesFinished=true;restoreOwnSurfaces();recycleOverlayImages();writingStarted=true;
        writer.execute(this::writeResults);
    }

    private void writeResults(){
        int saved=0;List<String> errors=new ArrayList<>();
        String timestamp=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date());
        try{
            for(int i=0;i<results.size();i++){
                if(cancelled)break;
                CaptureResult result=results.get(i);
                if(result.bitmap==null){errors.add(label(result.displayId)+" capture "+result.error);continue;}
                if(savePng(result,timestamp)){saved++;}else errors.add(label(result.displayId)+" save failed");
            }
        }finally{
            recycleCaptureResults();
        }
        if(cancelled)return;
        final String message=summary(saved,errors);
        synchronized(uriLock){finished=true;}
        main.post(()->{if(!cancelled)listener.finished(message);});
        writer.shutdown();
    }

    private boolean savePng(CaptureResult result,String timestamp){
        String name="DuoDash_"+label(result.displayId)+"_"+timestamp+".png";
        ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,name);
        values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/DuoDash");
        values.put(MediaStore.Images.Media.IS_PENDING,1);
        Uri uri=null;boolean published=false;
        try{
            uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            if(uri==null)return false;
            synchronized(uriLock){if(cancelled){context.getContentResolver().delete(uri,null,null);return false;}pendingUris.add(uri);}
            try(OutputStream output=context.getContentResolver().openOutputStream(uri,"w")){
                if(output==null||cancelled||!result.bitmap.compress(Bitmap.CompressFormat.PNG,100,output))return false;
            }
            if(cancelled)return false;
            ContentValues ready=new ContentValues();ready.put(MediaStore.Images.Media.IS_PENDING,0);
            if(context.getContentResolver().update(uri,ready,null,null)<=0)return false;
            published=true;
            synchronized(uriLock){pendingUris.remove(uri);if(cancelled){context.getContentResolver().delete(uri,null,null);return false;}}
            return true;
        }catch(Exception|OutOfMemoryError failure){return false;}
        finally{
            if(uri!=null&&!published){try{context.getContentResolver().delete(uri,null,null);}catch(RuntimeException ignored){}synchronized(uriLock){pendingUris.remove(uri);}}
        }
    }

    private String summary(int saved,List<String> errors){
        if(saved==displayIds.length){
            if(saved==1)return "Saved "+label(displayIds[0]).toLowerCase(Locale.ROOT)+" screenshot to Pictures/DuoDash";
            return "Saved main and external screenshots to Pictures/DuoDash";
        }
        if(saved>0){
            StringBuilder message=new StringBuilder("Saved ").append(saved).append(" screenshot").append(saved==1?"":"s").append("; ");
            for(int i=0;i<errors.size();i++){if(i>0)message.append("; ");message.append(errors.get(i));}
            return message.toString();
        }
        return errors.isEmpty()?"No screenshot was saved":("No screenshots saved: "+errors.get(0));
    }

    private static String label(int displayId){return displayId==0?"Main":"External";}

    private void finishWithMessage(String message){
        if(finished||cancelled)return;finished=true;listener.finished(message);writer.shutdown();
    }

    private void recycleOverlayImages(){for(OverlayImage image:overlayImages)if(!image.bitmap.isRecycled())image.bitmap.recycle();overlayImages.clear();}
    private void recycleCaptureResults(){for(CaptureResult result:results)if(result.bitmap!=null&&!result.bitmap.isRecycled())result.bitmap.recycle();results.clear();}

    private static final class SurfaceState{
        final View view;final int visibility;
        SurfaceState(View view,int visibility){this.view=view;this.visibility=visibility;}
    }
    private static final class OverlayImage{
        final int displayId,x,y,width,height;final Bitmap bitmap;
        OverlayImage(int displayId,int x,int y,int width,int height,Bitmap bitmap){this.displayId=displayId;this.x=x;this.y=y;this.width=width;this.height=height;this.bitmap=bitmap;}
    }
    private static final class CaptureResult{
        final int displayId;final Bitmap bitmap;final String error;
        CaptureResult(int displayId,Bitmap bitmap,String error){this.displayId=displayId;this.bitmap=bitmap;this.error=error;}
    }
}
