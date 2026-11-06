package dev.nova.deck;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

final class AppPicker {
    private final OverlayService host;private final DeckController controller;
    AppPicker(OverlayService host,DeckController controller){this.host=host;this.controller=controller;}
    void show(boolean pin){show(pin,null,controller.layoutKey());}
    void showReplace(String oldId,String key){show(false,oldId,key);}
    private void show(boolean pin,String replaceOld,String key){
        Context ui=host.dialogContext();PackageManager pm=host.getPackageManager();
        List<ResolveInfo> apps=new ArrayList<>(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0));
        apps.removeIf(app->!app.activityInfo.exported||!app.activityInfo.enabled||app.activityInfo.packageName.equals(host.getPackageName()));
        apps.sort((a,b)->a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        LinearLayout body=new LinearLayout(ui);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(host.dp(16),0,host.dp(16),0);
        RadioGroup screen=new RadioGroup(ui);screen.setOrientation(RadioGroup.HORIZONTAL);RadioButton main=new RadioButton(ui);main.setText("Main screen");main.setId(View.generateViewId());RadioButton upper=new RadioButton(ui);upper.setText("External screen");upper.setId(View.generateViewId());upper.setEnabled(pin||controller.state.externalConnected);screen.addView(main,new RadioGroup.LayoutParams(0,host.dp(48),1));
        if(!controller.dockedMode()){screen.addView(upper,new RadioGroup.LayoutParams(0,host.dp(48),1));if(controller.state.externalConnected)screen.check(controller.state.useExternalApps&&controller.state.externalConnected?upper.getId():main.getId());else screen.check(main.getId());body.addView(screen);}
        ListView list=new ListView(ui);List<String> labels=new ArrayList<>();for(ResolveInfo app:apps)labels.add(app.loadLabel(pm).toString());list.setAdapter(new ArrayAdapter<>(ui,android.R.layout.simple_list_item_1,labels));body.addView(list,new LinearLayout.LayoutParams(-1,host.dp(245)));
        AlertDialog dialog=new AlertDialog.Builder(ui).setTitle(pin?"Add an app":replaceOld!=null?"Replace with app":"Open app").setView(body).setNegativeButton("Cancel",null).create();host.showDialog(dialog);
        list.setOnItemClickListener((parent,view,position,id)->{
            ResolveInfo app=apps.get(position);ComponentName component=new ComponentName(app.activityInfo.packageName,app.activityInfo.name);boolean onUpper=!controller.dockedMode()&&controller.state.externalConnected&&screen.getCheckedRadioButtonId()==upper.getId();
            String appId=idFor(component.flattenToString()+":"+onUpper);UiState.AppShortcut shortcut=new UiState.AppShortcut(appId,ControlLogic.normalizeName(app.loadLabel(pm).toString(),"App"),component.flattenToString(),onUpper);
            if(replaceOld!=null){controller.replaceControlWithApp(key,replaceOld,shortcut);dialog.dismiss();}
            else if(pin){controller.pin(key,shortcut);host.refreshEditLayout();host.showEditor();}
            else host.launchApp(component,onUpper);
        });
    }
    static String idFor(String input){try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));StringBuilder text=new StringBuilder("app_");for(int i=0;i<12;i++)text.append(String.format(Locale.ROOT,"%02x",hash[i]&255));return text.toString();}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
