package dev.nova.deck;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.android.AdbMdns;

/** Pairs and connects only to an ADB daemon announced on this device's active Wi-Fi interface. */
final class OnDeviceBridge {
    static final String PREFS="bridge_activation";
    static final String PAIRED="paired";
    static final class PairingAcceptedException extends Exception {
        PairingAcceptedException(String message,Exception cause){super(message,cause);}
    }
    private OnDeviceBridge(){}

    static boolean isPaired(Context context){return context.getSharedPreferences(PREFS,0).getBoolean(PAIRED,false);}

    static String pairAndStart(Context context,String pairingCode)throws Exception{return pairAndStart(context,pairingCode,0);}

    static String pairAndStart(Context context,String pairingCode,int pairingPort)throws Exception{
        if(pairingCode==null||!pairingCode.matches("[0-9]{6}"))throw new IllegalArgumentException("Enter the six-digit code shown by Wireless debugging.");
        if(pairingPort<0||pairingPort>65535)throw new IllegalArgumentException("The Wireless debugging pairing port is invalid.");
        // ACTION_PAIR is an explicit user request to establish or replace trust.
        // Ordinary reconnects use reconnectAndStart(); do not silently reinterpret a
        // submitted fresh pairing code as a reconnect just because an older pairing
        // record exists.
        OnDeviceAdb adb=OnDeviceAdb.get(context);
        try{
            adb.setTimeout(10,TimeUnit.SECONDS);
            if(pairingPort>0){
                // The user supplied the port displayed by this device's active pairing dialog.
                // Connect only to loopback; never accept a host/IP or scan the LAN.
                adb.pair("127.0.0.1",pairingPort,pairingCode);
            }else{
                FoundService pairing=discover(context,AdbMdns.SERVICE_TYPE_TLS_PAIRING,25000);
                if(pairing==null)throw new IllegalStateException("Android did not publish its pairing service. Keep the pairing dialog open and retry with the six-digit code followed by the pairing port shown in Settings.");
                adb.pair(pairing.host.getHostAddress(),pairing.port,pairingCode);
            }
            context.getSharedPreferences(PREFS,0).edit().putBoolean(PAIRED,true).apply();
            try{return connectAndStart(context,adb,false);}
            catch(Exception error){
                throw new PairingAcceptedException("Pairing was saved, but system controls are still connecting.",error);
            }
        }finally{try{adb.disconnect();}catch(Exception ignored){}}
    }

    static String reconnectAndStart(Context context)throws Exception{
        if(!isPaired(context))throw new IllegalStateException("Pair DuoDash once in Wireless debugging first.");
        String healthy=healthyHelperMessage(context);if(healthy!=null)return healthy;
        return connectAndStart(context,OnDeviceAdb.get(context),false);
    }

    static String reconnectAfterBoot(Context context)throws Exception{
        if(!isPaired(context))throw new IllegalStateException("Pair DuoDash once in Wireless debugging first.");
        String healthy=healthyHelperMessage(context);if(healthy!=null)return healthy;
        return connectAndStart(context,OnDeviceAdb.get(context),true);
    }

    private static String healthyHelperMessage(Context context){
        return currentHelper(context)?"DuoDash system controls are already connected.":null;
    }

    private static boolean currentHelper(Context context){
        try{
            return new BridgeClient(context).isHealthy();
        }catch(Exception ignored){}
        return false;
    }

    private static String connectAndStart(Context context,OnDeviceAdb adb,boolean bootRecovery)throws Exception{
        String healthy=healthyHelperMessage(context);if(healthy!=null)return healthy;
        android.content.ContentResolver resolver=context.getContentResolver();
        int originalDebugging=android.provider.Settings.Global.getInt(resolver,"adb_wifi_enabled",0);
        boolean canWrite=context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)==android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean localNetwork=hasLocalNetwork(context);
        BridgeRecoveryPolicy.validateTemporaryEnable(originalDebugging,canWrite,localNetwork);
        return BridgeRecoveryPolicy.runWithTemporaryDebugging(originalDebugging,canWrite,localNetwork,
            new BridgeRecoveryPolicy.RecoveryOperations(){
                @Override public void enableWirelessDebugging()throws Exception{
                    if(!android.provider.Settings.Global.putInt(resolver,"adb_wifi_enabled",1)
                        ||android.provider.Settings.Global.getInt(resolver,"adb_wifi_enabled",0)!=1)
                        throw new SecurityException("Android blocked DuoDash from temporarily enabling Wireless debugging.");
                }
                @Override public String connectAndStart()throws Exception{return connectAndStartOverAdb(context,adb,bootRecovery);}
                @Override public void disconnectAdb()throws Exception{adb.disconnect();}
                @Override public void restoreWirelessDebugging(int value)throws Exception{
                    if(!android.provider.Settings.Global.putInt(resolver,"adb_wifi_enabled",value)
                        ||android.provider.Settings.Global.getInt(resolver,"adb_wifi_enabled",0)!=value)
                        throw new SecurityException("Android did not restore Wireless debugging to its original state.");
                }
                @Override public int readWirelessDebugging(){return android.provider.Settings.Global.getInt(resolver,"adb_wifi_enabled",0);}
                @Override public boolean helperHealthy(){
                    boolean healthy=currentHelper(context);
                    if((context.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0){
                        int restored=android.provider.Settings.Global.getInt(resolver,"adb_wifi_enabled",0);
                        android.util.Log.d("NovaDeckRecovery","Wireless debugging restore verified: original="
                            +originalDebugging+", restored="+restored+", helperHealthy="+healthy);
                    }
                    return healthy;
                }
            });
    }

    private static String connectAndStartOverAdb(Context context,OnDeviceAdb adb,boolean bootRecovery)throws Exception{
        String healthy=healthyHelperMessage(context);if(healthy!=null)return healthy;
        String existingToken=readExistingToken(context);
        adb.setTimeout(bootRecovery?3:10,TimeUnit.SECONDS);
        grantPersistentSettingsPermission(context,adb,bootRecovery);
        healthy=healthyHelperMessage(context);if(healthy!=null)return healthy;
            // Some Android builds keep the TLS listener alive while omitting its mDNS
            // record. Prefer its fixed loopback-only port; never scan the LAN.
            int localPort=localTlsPort();boolean connected=localPort>0&&adb.connect("127.0.0.1",localPort);
            FoundService service=null;
            if(!connected){service=discover(context,AdbMdns.SERVICE_TYPE_TLS_CONNECT,bootRecovery?2500:5000);if(service!=null)connected=adb.connect(service.host.getHostAddress(),service.port);}
            if(!connected&&!bootRecovery){
                for(int attempt=0;attempt<2&&!connected;attempt++){
                    Thread.sleep(1500L);
                    localPort=localTlsPort();if(localPort>0)connected=adb.connect("127.0.0.1",localPort);
                    if(!connected){service=discover(context,AdbMdns.SERVICE_TYPE_TLS_CONNECT,5000);if(service!=null)connected=adb.connect(service.host.getHostAddress(),service.port);}
                }
            }
            if(!connected)throw new IllegalStateException("Pairing is saved, but Android has not advertised its Wireless debugging connection service. Keep Wireless debugging on and tap Reconnect; if it stays unavailable, toggle Wireless debugging off and on in Developer options.");
            String token=existingToken==null?token():existingToken;
            // Keep a newly written random session token if startup confirmation is
            // transiently lost; the app can retry with the same credential.
            if(existingToken==null)writeToken(context,token);
            String apk=context.getApplicationInfo().sourceDir;
            String command=BridgeBootstrapCommand.build(apk,Process.myUid(),token);
            String response="";Exception shellFailure=null;
            try{response=shell(adb,command);}catch(Exception error){shellFailure=error;}
            long deadline=SystemClock.elapsedRealtime()+5000;
            while(SystemClock.elapsedRealtime()<deadline){
                try{
                    if(new BridgeClient(context).isHealthy())return "System controls connected.";
                }catch(Exception ignored){}
                Thread.sleep(200);
            }
            if(shellFailure!=null)throw new IllegalStateException("Android closed the startup stream before DuoDash could confirm its control service.",shellFailure);
            if(!response.matches("(?s).*\\b[0-9]+\\b.*"))throw new IllegalStateException("The Android shell did not start DuoDash's control service.");
        throw new IllegalStateException("DuoDash started, but its controls did not respond.");
    }

    private static String readExistingToken(Context context)throws Exception{
        File file=new File(context.getFilesDir(),"bridge-token");if(!file.exists())return null;
        if(!file.isFile())throw new IllegalStateException("DuoDash's saved helper session is not a regular private file.");
        String value=new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8).trim();
        if(!value.matches("[a-f0-9]{64}"))throw new IllegalStateException("DuoDash's saved helper session is invalid; the saved pairing was left intact.");
        return value;
    }
    private static void writeToken(Context context,String value)throws Exception{
        try(OutputStream output=context.openFileOutput("bridge-token",Context.MODE_PRIVATE)){output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));output.flush();}
    }

    private static void grantPersistentSettingsPermission(Context context,OnDeviceAdb adb,boolean bootRecovery)throws Exception{
        if(context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)==android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        if(!isPaired(context))throw new SecurityException("Pair DuoDash with Wireless debugging before enabling persistent reconnect.");
        int port=localTlsPort();boolean connected=port>0&&adb.connect("127.0.0.1",port);
        if(!connected){FoundService service=discover(context,AdbMdns.SERVICE_TYPE_TLS_CONNECT,bootRecovery?2500:5000);if(service!=null)connected=adb.connect(service.host.getHostAddress(),service.port);}
        if(!connected)throw new SecurityException("Persistent reconnect permission is not granted. Reconnect while this device's paired Wireless debugging service is available.");
        shell(adb,"pm grant dev.nova.deck android.permission.WRITE_SECURE_SETTINGS");
        if(context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Android did not grant DuoDash permission to restore Wireless debugging after restart.");
    }

    private static boolean hasLocalNetwork(Context context){
        android.net.ConnectivityManager connectivity=(android.net.ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.Network active=connectivity==null?null:connectivity.getActiveNetwork();
        android.net.NetworkCapabilities capabilities=active==null||connectivity==null?null:connectivity.getNetworkCapabilities(active);
        return capabilities!=null&&(capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            ||capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    static int localTlsPort(){
        java.lang.Process process=null;
        try{
            process=new ProcessBuilder("/system/bin/getprop","service.adb.tls.port").redirectErrorStream(true).start();
            if(!process.waitFor(750,TimeUnit.MILLISECONDS)){process.destroyForcibly();return 0;}
            if(process.exitValue()!=0)return 0;
            byte[] bytes=process.getInputStream().readNBytes(32);
            return parseLocalTlsPort(new String(bytes,java.nio.charset.StandardCharsets.US_ASCII));
        }catch(Exception ignored){return 0;}
        finally{if(process!=null)process.destroy();}
    }

    static int parseLocalTlsPort(String value){
        if(value==null)return 0;
        try{int port=Integer.parseInt(value.trim());return port>=1&&port<=65535?port:0;}
        catch(NumberFormatException ignored){return 0;}
    }

    private static String shell(OnDeviceAdb adb,String command)throws Exception{
        try(AdbStream stream=adb.openStream("shell:"+command);InputStream input=stream.openInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[512];int n;while((n=input.read(buffer))!=-1){if(output.size()+n>4096)throw new IllegalStateException("ADB startup response was too large.");output.write(buffer,0,n);}
            return output.toString(java.nio.charset.StandardCharsets.UTF_8.name()).trim();
        }
    }

    private static FoundService discover(Context context,String serviceType,long timeout)throws Exception{
        Set<InetAddress> wifiAddresses=wifiAddresses(context);if(wifiAddresses.isEmpty())return null;
        CountDownLatch ready=new CountDownLatch(1);FoundService[] found={null};
        AdbMdns mdns=new AdbMdns(context,serviceType,(host,port)->{
            if(port>0&&WifiAddressMatcher.isLocalWifiAddress(host,wifiAddresses)){synchronized(found){if(found[0]==null){found[0]=new FoundService(host,port);ready.countDown();}}}
        });
        mdns.start();try{ready.await(timeout,TimeUnit.MILLISECONDS);}finally{mdns.stop();}
        return found[0];
    }

    private static Set<InetAddress> wifiAddresses(Context context){
        Set<InetAddress> addresses=new HashSet<>();
        try{
            ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            for(Network network:manager.getAllNetworks()){
                NetworkCapabilities capabilities=manager.getNetworkCapabilities(network);
                if(capabilities==null||!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;
                LinkProperties links=manager.getLinkProperties(network);if(links==null)continue;
                for(android.net.LinkAddress link:links.getLinkAddresses()){InetAddress address=link.getAddress();if(address!=null&&!address.isLoopbackAddress()&&!address.isAnyLocalAddress())addresses.add(address);}
            }
        }catch(Exception ignored){}
        return addresses;
    }

    private static String token(){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);StringBuilder value=new StringBuilder(64);for(byte b:bytes)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
    private static final class FoundService{final InetAddress host;final int port;FoundService(InetAddress host,int port){this.host=host;this.port=port;}}
}
