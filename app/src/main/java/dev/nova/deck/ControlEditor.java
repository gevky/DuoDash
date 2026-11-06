package dev.nova.deck;

import android.app.AlertDialog;
import android.content.Context;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Adds and removes controls; the dashboard handles arrangement and edits use the shared dashboard layout. */
final class ControlEditor {
    private final OverlayService host;
    private final DeckController controller;
    private final Context ui;
    private final String key;
    private final ArrayList<String> order;
    private final Set<String> hidden;
    ControlEditor(OverlayService host,DeckController controller){this.host=host;this.controller=controller;ui=host.dialogContext();key=controller.layoutKey();order=new ArrayList<>(Arrays.asList(ControlLogic.cleanOrder(controller.state.controlOrder).split(",")));hidden=set(controller.state.hiddenControls);}
    private static Set<String> set(String value){Set<String> result=new LinkedHashSet<>();if(value!=null&&!value.isEmpty())result.addAll(Arrays.asList(value.split(",")));return result;}
    private boolean select(String id,boolean checked){
        if(!key.equals(controller.layoutKey())){host.message("Output changed. Reopen Add controls.");return true;}
        syncFromController();boolean newlyShown=checked&&hidden.contains(id);
        if(!checked&&ControlCatalog.isApp(id)){
            controller.removeControl(key,id);
            if(key.equals(controller.layoutKey())){
                order.clear();order.addAll(Arrays.asList(ControlLogic.cleanOrder(controller.state.controlOrder).split(",")));
                hidden.clear();hidden.addAll(set(controller.state.hiddenControls));
                host.refreshEditLayout();
            }
            return true;
        }
        if(checked)hidden.remove(id);else hidden.add(id);
        if(newlyShown){String updated=ControlLogic.appendControlToGroupEnd(String.join(",",order),String.join(",",hidden),id);order.clear();order.addAll(Arrays.asList(updated.split(",")));}
        save();return true;
    }
    private int dp(int n){return host.dp(n);}
    private void syncFromController(){
        order.clear();order.addAll(Arrays.asList(ControlLogic.cleanOrder(controller.state.controlOrder).split(",")));
        hidden.clear();hidden.addAll(set(controller.state.hiddenControls));
    }
    private void save(){controller.updateLayout(key,String.join(",",order),String.join(",",hidden));if(key.equals(controller.layoutKey())){syncFromController();host.refreshEditLayout();}}
    void replace(String oldId,String stableKey){
        ControlCatalog.Entry old=ControlCatalog.find(oldId);String kind=old==null?"action":old.kind;List<String> ids=new ArrayList<>(),labels=new ArrayList<>();
        Set<String> inactive=set(controller.state.hiddenControls);
        for(ControlCatalog.Entry entry:ControlCatalog.entries()){
            if(!ControlLogic.visibleOnDisplay(entry.id,controller.state.externalConnected))continue;
            if(!entry.kind.equals(kind)||entry.id.equals(oldId)||!inactive.contains(entry.id))continue;
            if((entry.id.equals("fan")||entry.id.equals("performance"))){UiState.ControlStatus status=controller.state.controls.get(entry.id);if(status==null||!status.available)continue;}
            ids.add(entry.id);labels.add(entry.label);
        }
        AlertDialog.Builder builder=new AlertDialog.Builder(ui).setTitle("Replace control").setNegativeButton("Cancel",null);
        if(labels.isEmpty())builder.setMessage(kind.equals("action")?"No inactive buttons are available. Choose an app instead.":"No inactive controls of this type are available.");
        else builder.setItems(labels.toArray(new String[0]),(dialog,which)->{controller.replaceControl(stableKey,oldId,ids.get(which));host.message("Control replaced in place");});
        if(kind.equals("action"))builder.setNeutralButton("Choose app",(dialog,which)->new AppPicker(host,controller).showReplace(oldId,stableKey));
        host.showDialog(builder.create());
    }
    private TextView text(String value,int size,int color){TextView view=new TextView(ui);view.setText(value);view.setTextSize(size);view.setTextColor(color);return view;}
    private Button button(String label,Runnable action){Button button=new Button(ui);button.setText(label);button.setAllCaps(false);button.setMinWidth(0);button.setMinimumWidth(0);button.setOnClickListener(v->action.run());return button;}
    private LinearLayout column(){LinearLayout view=new LinearLayout(ui);view.setOrientation(LinearLayout.VERTICAL);return view;}
    void show(){
        LinearLayout body=column();body.setPadding(dp(16),0,dp(16),0);
        EditText search=new EditText(ui);search.setSingleLine(true);search.setHint("Search controls");search.setTextSize(15);body.addView(search,new LinearLayout.LayoutParams(-1,dp(44)));
        LinearLayout tools=new LinearLayout(ui);tools.addView(button("Add an app",()->host.chooseApp(true)),new LinearLayout.LayoutParams(-1,dp(44)));body.addView(tools);
        LinearLayout entries=column();ScrollView scroll=new ScrollView(ui);scroll.addView(entries);body.addView(scroll,new LinearLayout.LayoutParams(-1,dp(215)));
        Runnable populate=()->{
            entries.removeAllViews();String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);String previous="";
            for(ControlCatalog.Entry entry:ControlCatalog.entries()){
                if(!ControlLogic.visibleOnDisplay(entry.id,controller.state.externalConnected))continue;
                if(!(entry.label+" "+entry.description+" "+entry.group).toLowerCase(Locale.ROOT).contains(query))continue;
                if(!entry.group.equals(previous)){TextView section=text(entry.group,13,0xff75E6DE);section.setPadding(dp(6),dp(12),0,dp(4));entries.addView(section);previous=entry.group;}
                boolean detected=!(entry.id.equals("fan")||entry.id.equals("performance"))||controller.state.controls.containsKey(entry.id)&&controller.state.controls.get(entry.id).available;
                LinearLayout row=new LinearLayout(ui);row.setGravity(Gravity.CENTER_VERTICAL);
                CheckBox check=new CheckBox(ui);check.setChecked(!hidden.contains(entry.id));check.setContentDescription(entry.label);check.setEnabled(detected);row.addView(check,new LinearLayout.LayoutParams(dp(44),dp(56)));
                LinearLayout copy=column();copy.setGravity(Gravity.CENTER_VERTICAL);copy.addView(text(entry.label,15,detected?0xffF1F4FA:0xffA6B2C5));copy.addView(text(detected?entry.description:"Not detected on the current connection",12,0xffA6B2C5));row.addView(copy,new LinearLayout.LayoutParams(0,dp(56),1));
                check.setOnCheckedChangeListener((b,checked)->{if(!select(entry.id,checked))check.setChecked(false);});row.setOnClickListener(v->{if(detected)check.setChecked(!check.isChecked());});entries.addView(row);
            }
            for(UiState.AppShortcut app:controller.state.pinnedApps){if(!app.label.toLowerCase(Locale.ROOT).contains(query))continue;CheckBox check=new CheckBox(ui);check.setText(app.label+(app.upper?" · External":" · Main"));check.setChecked(!hidden.contains(app.id));check.setMinHeight(dp(48));check.setOnCheckedChangeListener((b,checked)->{if(!select(app.id,checked))check.setChecked(true);else if(!checked)search.setText(search.getText());});entries.addView(check);}
        };
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){populate.run();}public void afterTextChanged(Editable e){}});populate.run();
        AlertDialog dialog=new AlertDialog.Builder(ui).setTitle("Add controls").setView(body).setPositiveButton("Done",null).create();host.showDialog(dialog);
    }
}
