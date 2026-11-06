package dev.nova.deck;

/** Produces only the fixed command that starts this APK's restricted bridge. */
final class BridgeBootstrapCommand {
    private BridgeBootstrapCommand(){}
    static String build(String apkPath,int appUid,String token){
        if(apkPath==null||!apkPath.startsWith("/data/app/")||appUid<10000||token==null||!token.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid DuoDash bootstrap inputs");
        return "for p in $(pidof nova_deck 2>/dev/null); do kill \"$p\" 2>/dev/null; done; "
            +"CLASSPATH="+quote(apkPath)+" nohup app_process /system/bin --nice-name=nova_deck dev.nova.deck.bridge.BridgeMain "
            +appUid+" "+quote(token)+" >/data/local/tmp/nova-deck-bridge.log 2>&1 </dev/null & sleep 1; pidof nova_deck";
    }
    static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
}
