package dev.nova.deck;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Best-effort reconnect for completed installations with a saved paired identity. */
public final class BridgeBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        String action=intent==null?"":intent.getAction();
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action)&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))return;
        SetupReadiness.Status readiness=SetupReadiness.inspect(context,false);
        if(readiness.canReconnectInBackground())BridgeActivationService.connectAfterBoot(context);
    }
}
