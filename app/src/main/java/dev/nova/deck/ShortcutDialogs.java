package dev.nova.deck;

import android.app.AlertDialog;
import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.*;

final class ShortcutDialogs {
    static String label(String chord){
        List<String> labels=new ArrayList<>();for(String key:chord.split(","))if(!key.isEmpty())labels.add(KeyEvent.keyCodeToString(Integer.parseInt(key)).replace("KEYCODE_","").replace("BUTTON_","").replace('_',' '));
        return String.join(" + ",labels);
    }
    static void show(OverlayService host){
        Context ui=host.dialogContext();LinearLayout rows=new LinearLayout(ui);rows.setOrientation(LinearLayout.VERTICAL);rows.setPadding(host.dp(20),0,host.dp(20),0);
        String saved=ShortcutMatcher.normalize(host.getSharedPreferences("ui",0).getString("shortcuts",""));
        TextView note=new TextView(ui);note.setText(saved.isEmpty()?"Add a button or a combination of up to three buttons. The same shortcut opens and closes DuoDash.":"Each shortcut opens and closes DuoDash. Tap × to remove one. Controller buttons also reach your game; avoid its existing shortcuts.");note.setTextSize(14);note.setTextColor(0xffA6B2C5);rows.addView(note);
        if(!saved.isEmpty())for(String chord:saved.split(";")){
            LinearLayout row=new LinearLayout(ui);row.setGravity(Gravity.CENTER_VERTICAL);
            TextView text=new TextView(ui);text.setText(label(chord));text.setTextSize(16);row.addView(text,new LinearLayout.LayoutParams(0,host.dp(48),1));
            Button remove=new Button(ui);remove.setText("×");remove.setContentDescription("Remove "+label(chord));row.addView(remove,new LinearLayout.LayoutParams(host.dp(48),host.dp(48)));
            remove.setOnClickListener(v->{List<String> values=new ArrayList<>(Arrays.asList(saved.split(";")));values.remove(chord);save(host,String.join(";",values));show(host);});rows.addView(row);
        }
        ScrollView scroll=new ScrollView(ui);scroll.addView(rows);
        AlertDialog dialog=new AlertDialog.Builder(ui).setTitle("Button shortcuts").setView(scroll).setPositiveButton("Done",null)
            .setNeutralButton("Add shortcut",(d,w)->record(host)).create();host.showDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(DeckAccessibilityService.connected!=null&&(saved.isEmpty()||saved.split(";").length<8));
        if(DeckAccessibilityService.connected==null)note.setText("Enable always-visible controls in Android Accessibility settings to record button shortcuts.");
    }
    private static void save(OverlayService host,String value){host.getSharedPreferences("ui",0).edit().putString("shortcuts",ShortcutMatcher.normalize(value)).apply();if(DeckAccessibilityService.connected!=null)DeckAccessibilityService.connected.reloadShortcuts();host.shortcutsChanged();}
    private static void record(OverlayService host){
        DeckAccessibilityService service=DeckAccessibilityService.connected;if(service==null)return;
        TextView note=new TextView(host.dialogContext());note.setText("Press one button, or hold two or three together. Release them, then tap Save. Home and Power are reserved by Android.");note.setTextSize(16);note.setPadding(host.dp(24),host.dp(8),host.dp(24),host.dp(12));
        TreeSet<Integer> held=new TreeSet<>(),candidate=new TreeSet<>();
        AlertDialog dialog=new AlertDialog.Builder(host.dialogContext()).setTitle("Record shortcut").setView(note).setPositiveButton("Save",null).setNegativeButton("Cancel",(d,w)->show(host)).create();
        DeckAccessibilityService.Recorder recorder=event->{
            if(event.getAction()==KeyEvent.ACTION_DOWN){if(event.getRepeatCount()>0)return;if(held.isEmpty())candidate.clear();held.add(event.getKeyCode());candidate.add(event.getKeyCode());}
            else held.remove(event.getKeyCode());
            String chord=join(candidate);note.setText((held.isEmpty()?"Ready: ":"Holding: ")+label(chord)+(candidate.size()>3?"\nChoose no more than three buttons.":""));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(held.isEmpty()&&!candidate.isEmpty()&&candidate.size()<=3);
        };
        host.showDialog(dialog);service.recorder=recorder;host.recordGamepad(true);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        dialog.setOnDismissListener(d->{if(service.recorder==recorder)service.recorder=null;host.dialogDismissed(dialog);});
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String existing=host.getSharedPreferences("ui",0).getString("shortcuts","");int[] keys=candidate.stream().mapToInt(Integer::intValue).toArray();
            if(!ShortcutMatcher.canAdd(existing,keys)){note.setText("This overlaps an existing shortcut. Choose a different button or combination.");return;}
            save(host,existing.isEmpty()?join(candidate):existing+";"+join(candidate));service.recorder=null;show(host);
        });
    }
    private static String join(Set<Integer> keys){List<String> text=new ArrayList<>();for(int key:keys)text.add(Integer.toString(key));return String.join(",",text);}
}
