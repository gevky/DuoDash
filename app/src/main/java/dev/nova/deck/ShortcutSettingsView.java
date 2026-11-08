package dev.nova.deck;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reusable shortcuts page content for the Settings sidebar. */
final class ShortcutSettingsView extends LinearLayout {
    private static final int TEXT=0xfff1eff4,MUTED=0xffb8b4be,ACCENT=0xffd2c6df,SURFACE=0xff1c1c20,ELEVATED=0xff27262b;
    private final OverlayService host;
    private final DeckController controller;
    private final LinearLayout content;
    private final Map<String,InlineShortcutEditor> editors=new LinkedHashMap<>();
    private final Map<String,String> drafts=new LinkedHashMap<>();
    private boolean pickerOpen,disposed;

    ShortcutSettingsView(Context context,OverlayService host,DeckController controller){
        super(context);this.host=host;this.controller=controller;setOrientation(VERTICAL);setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        content=this;setPadding(0,0,0,host.dp(8));render();
    }

    void dispose(){if(disposed)return;disposed=true;for(InlineShortcutEditor editor:new ArrayList<>(editors.values()))editor.dispose();editors.clear();drafts.clear();}
    boolean isRecording(){for(InlineShortcutEditor editor:editors.values())if(editor.isRecording())return true;return false;}
    boolean cancelPending(){InlineShortcutEditor active=host.inlineShortcutEditor;if(active!=null&&editors.containsValue(active))return active.cancelPending();for(InlineShortcutEditor editor:editors.values())if(editor.cancelPending())return true;return false;}
    List<InlineShortcutEditor> editors(){return new ArrayList<>(editors.values());}

    private void render(){
        if(disposed)return;
        for(InlineShortcutEditor editor:editors.values())editor.dispose();editors.clear();content.removeAllViews();
        TextView description=text("Hold up to three buttons, then release. Choose combinations your game doesn’t use.",13,MUTED,false);description.setLineSpacing(host.dp(2),1f);description.setPadding(0,0,0,host.dp(8));content.addView(description,new LayoutParams(-1,-2));
        ShortcutBindings bindings=bindings();
        addSection("DuoDash shortcut", "Opens and closes DuoDash", ShortcutBindings.TOGGLE, bindings.shortcuts(ShortcutBindings.TOGGLE), false);
        TextView controlsHeading=text("Control shortcuts",16,TEXT,true);LayoutParams h=new LayoutParams(-1,host.dp(38));h.topMargin=host.dp(10);content.addView(controlsHeading,h);
        Map<String,String> assigned=bindings.controls();TreeMap<String,String> sorted=new TreeMap<>(assigned);
        for(Map.Entry<String,String> entry:sorted.entrySet()){
            String id=entry.getKey();String label=ShortcutActions.label(id,controller.state);addSection(label,"Tap the shortcut to change it",id,entry.getValue(),false);
        }
        for(Map.Entry<String,String> draft:drafts.entrySet())if(!assigned.containsKey(draft.getKey()))addSection(draft.getValue(),"Choose a button combination for this control",draft.getKey(),"",true);
        Button add=button(pickerOpen?"Hide controls":"Add a control shortcut",false);LayoutParams addParams=new LayoutParams(-1,host.dp(46));addParams.topMargin=host.dp(8);content.addView(add,addParams);add.setOnClickListener(v->{pickerOpen=!pickerOpen;render();});
        if(pickerOpen)renderPicker(assigned);
    }

    private void addSection(String title,String detail,String actionId,String value,boolean draft){
        LinearLayout card=new LinearLayout(getContext());card.setOrientation(VERTICAL);card.setPadding(host.dp(12),host.dp(8),host.dp(12),host.dp(8));card.setBackground(round(SURFACE,10));LayoutParams cp=new LayoutParams(-1,-2);cp.topMargin=host.dp(6);content.addView(card,cp);
        TextView heading=text(title,14,TEXT,true);card.addView(heading,new LayoutParams(-1,-2));TextView sub=text(detail,12,MUTED,false);sub.setPadding(0,host.dp(2),0,host.dp(4));card.addView(sub,new LayoutParams(-1,-2));
        InlineShortcutEditor editor=new InlineShortcutEditor(getContext(),host,controller,actionId,()->{drafts.remove(actionId);render();});editors.put(actionId,editor);card.addView(editor,new LayoutParams(-1,-2));
        if(draft){Button remove=button("Remove control",false);LayoutParams rp=new LayoutParams(-1,host.dp(42));rp.topMargin=host.dp(4);card.addView(remove,rp);remove.setOnClickListener(v->{drafts.remove(actionId);render();});}
    }

    private void renderPicker(Map<String,String> assigned){
        LinearLayout picker=new LinearLayout(getContext());picker.setOrientation(VERTICAL);picker.setPadding(host.dp(12),host.dp(8),host.dp(12),host.dp(8));picker.setBackground(round(ELEVATED,10));LayoutParams pp=new LayoutParams(-1,-2);pp.topMargin=host.dp(6);content.addView(picker,pp);
        TextView hint=text("Choose a control",13,MUTED,false);picker.addView(hint,new LayoutParams(-1,host.dp(32)));
        Map<String,String> options=ShortcutActions.options(controller.state);List<Map.Entry<String,String>> available=new ArrayList<>();for(Map.Entry<String,String> option:options.entrySet())if(!assigned.containsKey(option.getKey())&&!drafts.containsKey(option.getKey()))available.add(option);available.sort(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER));
        if(available.isEmpty()){TextView empty=text("No more controls are available.",13,MUTED,false);picker.addView(empty,new LayoutParams(-1,host.dp(38)));return;}
        for(Map.Entry<String,String> option:available){Button item=button(option.getValue(),false);LayoutParams ip=new LayoutParams(-1,host.dp(44));ip.topMargin=host.dp(3);picker.addView(item,ip);item.setOnClickListener(v->{drafts.put(option.getKey(),option.getValue());pickerOpen=false;render();InlineShortcutEditor editor=editors.get(option.getKey());if(editor!=null)editor.post(editor::addBinding);});}
    }

    private ShortcutBindings bindings(){android.content.SharedPreferences prefs=host.getSharedPreferences("ui",Context.MODE_PRIVATE);return new ShortcutBindings(prefs.getString("shortcuts",""),prefs.getString("controlShortcuts",""));}
    private Button button(String label,boolean filled){Button b=new Button(getContext());b.setText(label);b.setTextSize(13);b.setAllCaps(false);b.setMinWidth(0);b.setPadding(host.dp(8),0,host.dp(8),0);b.setTextColor(filled?0xff101012:TEXT);android.graphics.drawable.StateListDrawable background=new android.graphics.drawable.StateListDrawable();GradientDrawable focus=round(ELEVATED,9);focus.setStroke(Math.max(1,host.dp(1)),ACCENT);background.addState(new int[]{android.R.attr.state_focused},focus);background.addState(new int[]{android.R.attr.state_pressed},round(0xff3c3742,9));background.addState(new int[]{},round(filled?ACCENT:ELEVATED,9));b.setBackground(background);return b;}
    private TextView text(String value,int size,int color,boolean medium){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setTypeface(android.graphics.Typeface.create(medium?"sans-serif-medium":"sans-serif",android.graphics.Typeface.NORMAL));t.setIncludeFontPadding(false);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(host.dp(radius));return d;}
}
