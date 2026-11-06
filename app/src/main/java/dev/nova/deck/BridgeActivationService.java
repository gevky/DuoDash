package dev.nova.deck;

import android.app.*;
import android.content.*;
import android.Manifest;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

/** Short-lived foreground task that pairs/reconnects this device's Wireless debugging ADB. */
public final class BridgeActivationService extends Service {
    static final String ACTION_CONNECT="dev.nova.deck.CONNECT_SYSTEM_CONTROLS";
    static final String ACTION_PAIR="dev.nova.deck.PAIR_SYSTEM_CONTROLS";
    static final String ACTION_CANCEL_PAIR="dev.nova.deck.CANCEL_SYSTEM_PAIRING";
    static final String EXTRA_BOOT_RECOVERY="boot_recovery";
    private static final String EXTRA_PANEL_REPLY="panel_pairing_reply";
    private static final String CHANNEL="system_reconnect";
    static final String PAIR_CHANNEL="system_pairing";
    private static final String REMOTE_CODE_KEY="duodash_pairing_code";
    private static final String EXTRA_PANEL_SESSION="panel_session";
    private static final int ID=53;
    private static final int PAIR_ID=54;
    private static final java.util.concurrent.atomic.AtomicBoolean ACTIVE=new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile String activeMessage="";

    static void connect(Context context){start(context,new Intent(context,BridgeActivationService.class).setAction(ACTION_CONNECT));}
    static void connectAfterBoot(Context context){start(context,new Intent(context,BridgeActivationService.class).setAction(ACTION_CONNECT).putExtra(EXTRA_BOOT_RECOVERY,true));}
    static boolean isConnecting(){return ACTIVE.get();}
    static String currentMessage(){return activeMessage;}
    static boolean preparePairing(Context context){
        createChannels(context);if(!pairingNotificationsAvailable(context))return false;
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        manager.notify(PAIR_ID,pairingNotification(context,"Open Wireless debugging and choose Pair device with pairing code. If discovery fails, reply with its six-digit code and the pairing port shown in Settings."));return true;
    }
    static void submitPanelPairing(Context context,String reply,long session){
        Intent intent=new Intent(context,BridgeActivationService.class).setAction(ACTION_PAIR)
            .putExtra(EXTRA_PANEL_REPLY,reply).putExtra(EXTRA_PANEL_SESSION,session);
        start(context,intent);
    }
    static boolean pairingNotificationsAvailable(Context context){
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return false;
        NotificationManager manager=context.getSystemService(NotificationManager.class);if(manager==null||!manager.areNotificationsEnabled())return false;
        if(Build.VERSION.SDK_INT>=26){NotificationChannel channel=manager.getNotificationChannel(PAIR_CHANNEL);if(channel!=null&&channel.getImportance()==NotificationManager.IMPORTANCE_NONE)return false;}
        return true;
    }
    private static void start(Context context,Intent intent){if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);else context.startService(intent);}

    @Override public void onCreate(){super.onCreate();createChannels(this);startForeground(ID,notification("Connecting to this device's Wireless debugging service",false));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        final String action=intent==null?ACTION_CONNECT:intent.getAction();
        if(ACTION_CANCEL_PAIR.equals(action)){getSystemService(NotificationManager.class).cancel(PAIR_ID);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);return START_NOT_STICKY;}
        final boolean afterBoot=intent!=null&&intent.getBooleanExtra(EXTRA_BOOT_RECOVERY,false);
        final long panelSession=intent==null?-1:intent.getLongExtra(EXTRA_PANEL_SESSION,-1);
        PairingReplyParser.Reply pairingReply=readPairingReply(intent);final char[] code=pairingReply.code;
        if(intent!=null){intent.setClipData(null);intent.replaceExtras((android.os.Bundle)null);}
        if(!ACTIVE.compareAndSet(false,true)){
            java.util.Arrays.fill(code,'\0');pairingReply.clear();
            if(panelSession>=0)new android.os.Handler(getMainLooper()).post(()->{
                if(DeckAccessibilityService.connected!=null)
                    DeckAccessibilityService.connected.pairingPanelResult(panelSession,false,"Another system-control operation is running. Try again in a moment.");
            });
            return START_NOT_STICKY;
        }
        activeMessage=ACTION_PAIR.equals(action)?"Pairing DuoDash…":"Restoring system controls…";
        new Thread(()->{
            String result="System controls could not reconnect.";boolean success=false;
            for(int attempt=0;attempt<BootReconnectPolicy.attempts(afterBoot);attempt++){
                long delay=BootReconnectPolicy.delayBeforeAttempt(afterBoot,attempt);
                if(delay>0){activeMessage="Waiting to restore system controls…";try{Thread.sleep(delay);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}}
                try{
                    if(ACTION_PAIR.equals(action)){
                        if(!pairingReply.valid)throw new IllegalArgumentException(pairingReply.error);
                        try{
                            result=OnDeviceBridge.pairAndStart(this,new String(code),pairingReply.port);
                            success=true;
                        }catch(OnDeviceBridge.PairingAcceptedException accepted){
                            result=accepted.getMessage();
                            for(int recovery=0;recovery<BootReconnectPolicy.MAX_PAIR_HELPER_RECOVERY_ATTEMPTS;recovery++){
                                long wait=BootReconnectPolicy.delayBeforePairHelperRecovery(recovery);
                                if(wait>0)try{Thread.sleep(wait);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}
                                try{result=OnDeviceBridge.reconnectAndStart(this);success=true;break;}
                                catch(Exception retryError){result=retryError.getMessage()==null?"Pairing was saved, but system controls are still connecting.":retryError.getMessage();}
                            }
                        }
                    }else result=afterBoot?OnDeviceBridge.reconnectAfterBoot(this):OnDeviceBridge.reconnectAndStart(this);
                    if(success||!ACTION_PAIR.equals(action))success=true;
                    break;
                }
                catch(Exception error){result=error.getMessage()==null?"System controls could not reconnect.":error.getMessage();activeMessage=result;}
            }
            java.util.Arrays.fill(code,'\0');
            final String message=result;final boolean connected=success;
            new android.os.Handler(getMainLooper()).post(()->{
                activeMessage=message;ACTIVE.set(false);
                if(ACTION_PAIR.equals(action)&&panelSession>=0&&DeckAccessibilityService.connected!=null)
                    DeckAccessibilityService.connected.pairingPanelResult(panelSession,connected,message);
                else if(connected&&DeckAccessibilityService.connected!=null)
                    DeckAccessibilityService.connected.pairingPanelConnected();
                if(OverlayService.current!=null){OverlayService.current.bridgeActivationFinished(connected,message);}
                publishCompletion(action,connected,message,afterBoot);
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            });
        },"duodash-adb-connect").start();
        return START_NOT_STICKY;
    }

    private static void createChannels(Context context){if(Build.VERSION.SDK_INT>=26){NotificationManager manager=context.getSystemService(NotificationManager.class);NotificationChannel channel=new NotificationChannel(CHANNEL,"DuoDash reconnect",NotificationManager.IMPORTANCE_LOW);channel.setDescription("On-device system-control reconnection");NotificationChannel pair=new NotificationChannel(PAIR_CHANNEL,"DuoDash first-time pairing",NotificationManager.IMPORTANCE_DEFAULT);pair.setDescription("Enter the temporary Wireless debugging code without leaving Android Settings.");manager.createNotificationChannel(channel);manager.createNotificationChannel(pair);}}
    private static PairingReplyParser.Reply readPairingReply(Intent intent){
        if(intent==null||!ACTION_PAIR.equals(intent.getAction()))return PairingReplyParser.parse(null);
        android.os.Bundle results=RemoteInput.getResultsFromIntent(intent);CharSequence value=results==null?null:results.getCharSequence(REMOTE_CODE_KEY);
        if(value==null)value=intent.getCharSequenceExtra(EXTRA_PANEL_REPLY);
        return PairingReplyParser.parse(value);
    }
    private void publishCompletion(String action,boolean success,String detail,boolean afterBoot){
        NotificationManager manager=getSystemService(NotificationManager.class);if(manager==null)return;
        if(success){manager.cancel(PAIR_ID);return;}
        if(ACTION_PAIR.equals(action)){
            if(OnDeviceBridge.isPaired(this))manager.notify(PAIR_ID,pairedRetryNotification(detail));
            else manager.notify(PAIR_ID,pairingNotification(this,detail+" Keep the Android dialog open and reply with the code, optionally followed by its pairing port."));
        }else if(!success&&BootReconnectPolicy.shouldPublishFailureNotification(afterBoot))manager.notify(ID,notification(detail,true));
    }
    private static PendingIntent settingsPending(Context context){Intent settings=new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);return PendingIntent.getActivity(context,54,settings,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
    private static PendingIntent cancelPairPending(Context context){Intent cancel=new Intent(context,BridgeActivationService.class).setAction(ACTION_CANCEL_PAIR);return PendingIntent.getService(context,55,cancel,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
    private static PendingIntent pairReplyPending(Context context){Intent pair=new Intent(context,BridgeActivationService.class).setAction(ACTION_PAIR);return PendingIntent.getForegroundService(context,56,pair,PendingIntent.FLAG_MUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
    private static Notification pairingNotification(Context context,String message){
        RemoteInput input=new RemoteInput.Builder(REMOTE_CODE_KEY).setLabel("Pairing code, optionally followed by its port").setAllowFreeFormInput(true).build();
        Notification.Action reply=new Notification.Action.Builder(null,"Enter pairing code",pairReplyPending(context)).addRemoteInput(input).setAllowGeneratedReplies(false).build();
        return new Notification.Builder(context,PAIR_CHANNEL).setSmallIcon(R.drawable.ic_nova).setContentTitle("DuoDash pairing ready").setContentText(message).setSubText("Reply with code or: 123456 37007").setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(settingsPending(context)).addAction(reply).addAction(new Notification.Action.Builder(null,"Cancel",cancelPairPending(context)).build()).build();
    }
    private Notification pairedRetryNotification(String message){
        Intent open=new Intent(this,MainActivity.class).putExtra("setup",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending=PendingIntent.getActivity(this,51,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent retry=PendingIntent.getForegroundService(this,52,new Intent(this,BridgeActivationService.class).setAction(ACTION_CONNECT),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,PAIR_CHANNEL).setSmallIcon(R.drawable.ic_nova).setContentTitle("DuoDash pairing saved").setContentText(message).setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(openPending).setAutoCancel(false).setOngoing(true).addAction(new Notification.Action.Builder(null,"Reconnect",retry).build()).addAction(new Notification.Action.Builder(null,"Developer options",settingsPending(this)).build()).build();
    }
    private Notification notification(String message,boolean retry){
        Intent open=new Intent(this,MainActivity.class).putExtra("setup",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending=PendingIntent.getActivity(this,51,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_nova).setContentTitle(retry?"DuoDash needs system controls":"DuoDash is reconnecting").setContentText(message).setContentIntent(openPending).setAutoCancel(retry).setOngoing(!retry);
        if(retry){PendingIntent retryPending=PendingIntent.getForegroundService(this,52,new Intent(this,BridgeActivationService.class).setAction(ACTION_CONNECT),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);builder.addAction(new Notification.Action.Builder(null,"Reconnect",retryPending).build());
            PendingIntent settings=PendingIntent.getActivity(this,54,new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);builder.addAction(new Notification.Action.Builder(null,"Developer options",settings).build());}
        return builder.build();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
