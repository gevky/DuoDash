package dev.nova.deck;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class BridgeClient {
    public static final String SOCKET="dev.nova.deck.control.v1";
    public static final int EXPECTED_BRIDGE_BUILD=49;
    public static final String EXPECTED_SWAP_STRATEGY="coordinated-roottask-v3-no-placeholder";
    private final Context context;
    public BridgeClient(Context context) { this.context=context; }
    public JSONObject request(String command,JSONObject arguments) throws Exception {
        File tokenFile=new File(context.getFilesDir(),"bridge-token");
        if(!tokenFile.exists()) throw new IOException("System controls need reconnecting");
        String token=new String(Files.readAllBytes(tokenFile.toPath()),StandardCharsets.UTF_8).trim();
        if(token.length()!=64) throw new IOException("System controls need reconnecting");
        JSONObject request=arguments==null?new JSONObject():new JSONObject(arguments.toString());
        request.put("command",command).put("token",token);
        try(LocalSocket socket=new LocalSocket()) {
            socket.connect(new LocalSocketAddress(SOCKET,LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(5000);
            socket.getOutputStream().write((request+"\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String result=readLine(socket.getInputStream(),65536);
            JSONObject response=new JSONObject(result);
            if(!response.optBoolean("ok")) throw new IOException(response.optString("error","Control unavailable"));
            return response;
        }
    }
    /** A cheap authenticated probe that does not depend on display, audio or telemetry APIs. */
    boolean isHealthy() throws Exception { return isCurrentHelper(request("ping",null)); }
    BridgeStatusPoll.Result<JSONObject> pollStatus() {
        return BridgeStatusPoll.read(()->{
            JSONObject status=request("status",null);
            if(!isCurrentHelper(status))throw new IOException("System controls need updating");
            return status;
        },this::isHealthy);
    }
    static boolean isCurrentHelper(JSONObject status){
        return status!=null&&isCurrentHelper(status.optInt("bridgeBuild",-1),status.optString("swapStrategy"));
    }
    static boolean isCurrentHelper(int build,String strategy){return build==EXPECTED_BRIDGE_BUILD&&EXPECTED_SWAP_STRATEGY.equals(strategy);}
    public static String readLine(InputStream input,int limit) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for(int c;(c=input.read())!=-1;) {
            if(c=='\n') return bytes.toString(StandardCharsets.UTF_8.name());
            if(bytes.size()>=limit) throw new IOException("Control response too large");
            bytes.write(c);
        }
        if(bytes.size()==0) throw new EOFException("System controls disconnected");
        return bytes.toString(StandardCharsets.UTF_8.name());
    }
}
