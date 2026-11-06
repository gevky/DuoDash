package dev.nova.deck;
import android.content.Intent;
import android.app.PendingIntent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
/** Optional Android Quick Settings shortcut; the same tile opens and closes the panel. */
public final class DeckTileService extends TileService {
    @Override public void onStartListening(){super.onStartListening();Tile tile=getQsTile();if(tile!=null){tile.setLabel("DuoDash");tile.setState(Tile.STATE_ACTIVE);tile.updateTile();}}
    @Override public void onClick(){
        super.onClick();
        SetupReadiness.Status readiness=SetupReadiness.inspect(this,false);
        if(readiness.isCompleted()&&readiness.hasLocalDashboardPrerequisites()){
            startForegroundService(new Intent(this,OverlayService.class).setAction(OverlayService.TOGGLE));
            return;
        }
        Intent wizard=new Intent(this,MainActivity.class).putExtra("tile_toggle",true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if(Build.VERSION.SDK_INT>=34){
            PendingIntent launch=PendingIntent.getActivity(this,77,wizard,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(launch);
        }else startActivityAndCollapse(wizard);
    }
}
