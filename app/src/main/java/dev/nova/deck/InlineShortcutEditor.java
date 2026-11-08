package dev.nova.deck;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** In-place editor for one shortcut action. */
final class InlineShortcutEditor extends LinearLayout {
    private enum CaptureState { IDLE, CAPTURING, READY, TOO_MANY, INVALID }
    private static final long CAPTURE_LIMIT_MS=20_000L;
    private static final int MAX_SHORTCUTS=8;

    private final OverlayService host;
    private final DeckController controller;
    private final String actionId;
    private final Runnable changed;
    private final LinearLayout bindings;
    private final TextView note,captureLabel;
    private final Button action;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable captureTimeout=()->finishCapture("Recording timed out. Try again.");
    private final TreeSet<Integer> held=new TreeSet<>(),candidate=new TreeSet<>();
    private DeckAccessibilityService.Recorder recorder;
    private CaptureState captureState=CaptureState.IDLE;
    private String replacingChord;
    private boolean disposed;

    /** Legacy constructor retained for the Dashboard category editor. */
    InlineShortcutEditor(Context context,OverlayService host,DeckController controller){
        this(context,host,controller,ShortcutBindings.TOGGLE,null);
    }

    InlineShortcutEditor(Context context,OverlayService host,DeckController controller,String actionId,Runnable changed){
        super(context);this.host=host;this.controller=controller;this.actionId=actionId;this.changed=changed;
        setOrientation(VERTICAL);setPadding(host.dp(4),host.dp(2),host.dp(4),host.dp(2));
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        note=new TextView(context);note.setTextSize(12);note.setTextColor(0xffb8b4be);addView(note,new LinearLayout.LayoutParams(-1,-2));
        bindings=new LinearLayout(context);bindings.setOrientation(VERTICAL);
        LinearLayout.LayoutParams listParams=new LinearLayout.LayoutParams(-1,-2);listParams.topMargin=host.dp(4);addView(bindings,listParams);
        captureLabel=new TextView(context);captureLabel.setTextSize(12);captureLabel.setTextColor(0xffb8b4be);captureLabel.setGravity(Gravity.CENTER_VERTICAL);captureLabel.setVisibility(GONE);
        LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(-1,-2);labelParams.topMargin=host.dp(4);addView(captureLabel,labelParams);
        action=new Button(context);action.setAllCaps(false);action.setTextSize(14);action.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xffa6a2ad,0xfff1eff4}));action.setBackgroundTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_pressed},new int[]{android.R.attr.state_focused},new int[]{}},new int[]{0xff514b58,0xff423d47,0xff302d33}));action.setMinHeight(host.dp(44));
        action.setOnClickListener(v->performAction());LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-1,host.dp(48));actionParams.topMargin=host.dp(2);addView(action,actionParams);
        renderBindings();renderAction();
    }

    boolean isRecording(){return captureState!=CaptureState.IDLE;}
    boolean isCapturing(){return captureState==CaptureState.CAPTURING;}
    boolean cancelPending(){if(captureState==CaptureState.IDLE)return false;finishCapture(null);return true;}
    public void dispose(){if(disposed)return;disposed=true;handler.removeCallbacks(captureTimeout);detachRecorder();clearActive();held.clear();candidate.clear();captureState=CaptureState.IDLE;}

    void addBinding(){if(captureState==CaptureState.IDLE){String replace=null;if(!dashboard()&&!saved().isEmpty())replace=saved().split(";")[0];beginCapture(replace);}}

    private ShortcutBindings model(){
        return new ShortcutBindings(host.getSharedPreferences("ui",Context.MODE_PRIVATE).getString("shortcuts",""),host.getSharedPreferences("ui",Context.MODE_PRIVATE).getString("controlShortcuts",""));
    }
    private String saved(){return model().shortcuts(actionId);}
    private boolean dashboard(){return ShortcutBindings.TOGGLE.equals(actionId);}

    private void renderBindings(){
        bindings.removeAllViews();String value=ShortcutMatcher.normalize(saved());
        List<String> chords=value.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(value.split(";")));
        if(!dashboard()&&chords.size()>1)chords=new ArrayList<>(chords.subList(0,1));
        if(chords.isEmpty()&&dashboard()){
            TextView empty=new TextView(getContext());empty.setText("Add a button combination. It opens and closes DuoDash.");empty.setTextSize(13);empty.setTextColor(0xffb8b4be);empty.setGravity(Gravity.CENTER_VERTICAL);bindings.addView(empty,new LinearLayout.LayoutParams(-1,host.dp(40)));
        }
        for(String chord:chords){
            String label=ShortcutLabels.label(chord);boolean delayed=model().waitsForRelease(chord);LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name=new TextView(getContext());name.setText(bindingLabel(label,delayed));name.setTextSize(14);name.setTextColor(0xfff1eff4);name.setGravity(Gravity.CENTER_VERTICAL);name.setSingleLine(true);name.setContentDescription("Replace "+label+(delayed?". Runs on release":""));name.setFocusable(true);name.setFocusableInTouchMode(false);name.setClickable(true);name.setBackground(removeBackground());name.setOnClickListener(v->beginCapture(chord));row.addView(name,new LinearLayout.LayoutParams(0,host.dp(48),1));
            TextView remove=new TextView(getContext());remove.setText("×");remove.setTextSize(19);remove.setTextColor(0xffd2c6df);remove.setGravity(Gravity.CENTER);remove.setMinWidth(host.dp(44));remove.setMinHeight(host.dp(44));remove.setFocusable(true);remove.setClickable(true);remove.setBackground(removeBackground());
            boolean canRemove=!dashboard()||chords.size()>1;remove.setContentDescription(canRemove?"Remove "+label:"At least one shortcut is required");remove.setEnabled(canRemove);remove.setAlpha(canRemove?1f:.45f);remove.setOnClickListener(v->{if(canRemove)removeShortcut(chord);});row.addView(remove,new LinearLayout.LayoutParams(host.dp(44),host.dp(44)));bindings.addView(row,new LinearLayout.LayoutParams(-1,host.dp(48)));
        }
    }

    private StateListDrawable removeBackground(){StateListDrawable states=new StateListDrawable();GradientDrawable focused=new GradientDrawable();focused.setColor(0xff29272d);focused.setStroke(Math.max(1,host.dp(1)),0xffd2c6df);GradientDrawable pressed=new GradientDrawable();pressed.setColor(0xff29272d);GradientDrawable normal=new GradientDrawable();normal.setColor(0x00000000);states.addState(new int[]{android.R.attr.state_focused},focused);states.addState(new int[]{android.R.attr.state_pressed},pressed);states.addState(new int[]{},normal);return states;}

    private CharSequence bindingLabel(String label,boolean delayed){
        if(!delayed)return label;
        android.text.SpannableString text=new android.text.SpannableString(label+" · on release");
        text.setSpan(new android.text.style.ForegroundColorSpan(0xffb8b4be),label.length(),text.length(),0);
        text.setSpan(new android.text.style.RelativeSizeSpan(.8f),label.length(),text.length(),0);
        return text;
    }

    private void renderAction(){
        if(disposed)return;
        boolean connected=DeckAccessibilityService.connected!=null&&controller.state.bridgeReady;
        int count=saved().isEmpty()?0:saved().split(";").length;
        note.setText(dashboard()?"Each combination opens and closes DuoDash.":"Record a button combination for this control.");
        note.setVisibility(!connected||(dashboard()&&count>=MAX_SHORTCUTS)?VISIBLE:GONE);
        action.setVisibility(!dashboard()&&count>0&&captureState==CaptureState.IDLE?GONE:VISIBLE);
        captureLabel.setVisibility(captureState==CaptureState.IDLE?GONE:VISIBLE);action.setEnabled(true);
        switch(captureState){
            case IDLE:action.setText(dashboard()?(count==0?"Record shortcut":"Add another shortcut"):(count==0?"Record shortcut":"Change shortcut"));action.setEnabled(connected&&(!dashboard()||count<MAX_SHORTCUTS));if(dashboard()&&count>=MAX_SHORTCUTS)note.setText("You can save up to eight shortcuts. Remove one to add another.");if(!connected)note.setText("Connect system controls and enable always-visible controls to record shortcuts.");break;
            case CAPTURING:action.setText("Cancel");captureLabel.setText(candidate.isEmpty()?"Press one to three buttons, then release them.":"Holding: "+ShortcutLabels.label(join(candidate))+(candidate.size()>3?" · use no more than three.":""));break;
            case READY:action.setText("Save shortcut");captureLabel.setText(ShortcutLabels.label(join(candidate))+" · press A to save or B to discard.");break;
            case TOO_MANY:action.setText("Try again");captureLabel.setText("Choose no more than three buttons.");break;
            case INVALID:action.setText("Try again");break;
        }
    }

    private void performAction(){if(disposed)return;switch(captureState){case IDLE:if(dashboard())beginCapture(null);else addBinding();break;case CAPTURING:finishCapture(null);break;case READY:saveCandidate();break;case TOO_MANY:case INVALID:beginCapture(replacingChord);break;}}
    private void beginCapture(String replacing){
        if(disposed)return;
        if(host.inlineShortcutEditor!=null&&host.inlineShortcutEditor!=this&&host.inlineShortcutEditor.isRecording())host.inlineShortcutEditor.cancelPending();
        DeckAccessibilityService service=DeckAccessibilityService.connected;
        if(service==null||!controller.state.bridgeReady){captureLabel.setText("Connect system controls and enable always-visible controls first.");captureLabel.setVisibility(VISIBLE);return;}
        if(dashboard()&&saved().split(";").length>=MAX_SHORTCUTS&&replacing==null){renderAction();return;}
        replacingChord=replacing;held.clear();candidate.clear();captureState=CaptureState.CAPTURING;host.inlineShortcutEditor=this;
        recorder=this::onRecordedEvent;service.recorder=recorder;host.recordGamepad(true);handler.removeCallbacks(captureTimeout);handler.postDelayed(captureTimeout,CAPTURE_LIMIT_MS);renderAction();
    }
    private void onRecordedEvent(KeyEvent event){
        if(disposed||event==null)return;int code=event.getKeyCode();if(code==KeyEvent.KEYCODE_HOME||code==KeyEvent.KEYCODE_POWER||code==KeyEvent.KEYCODE_UNKNOWN)return;if(captureState!=CaptureState.CAPTURING)return;
        if(event.getAction()==KeyEvent.ACTION_DOWN){if(event.getRepeatCount()>0)return;held.add(code);candidate.add(code);if(candidate.size()>3)captureLabel.setText("Holding: "+ShortcutLabels.label(join(candidate))+" · use no more than three.");}
        else if(event.getAction()==KeyEvent.ACTION_UP){held.remove(code);if(held.isEmpty()&&!candidate.isEmpty())candidateReleased();}
        if(captureState==CaptureState.CAPTURING)renderAction();
    }
    private void candidateReleased(){handler.removeCallbacks(captureTimeout);detachRecorder();captureState=candidate.size()<=3?CaptureState.READY:CaptureState.TOO_MANY;renderAction();action.requestFocus();}

    private void saveCandidate(){
        if(captureState!=CaptureState.READY||candidate.isEmpty())return;
        int[] keys=candidate.stream().mapToInt(Integer::intValue).toArray();ShortcutBindings bindings=model();String controlSerialized;
        try{
            String conflict=bindings.conflict(actionId,keys,replacingChord);
            if(!conflict.isEmpty())throw new IllegalArgumentException("Already used by "+ShortcutActions.label(conflict,controller.state)+". Choose different buttons.");
            if(dashboard()){
                String chord=join(candidate);
                List<String> values=new ArrayList<>();if(!bindings.shortcuts(actionId).isEmpty())values.addAll(Arrays.asList(bindings.shortcuts(actionId).split(";")));
                if(replacingChord!=null)values.remove(replacingChord);if(!values.contains(chord))values.add(chord);
                if(values.size()>MAX_SHORTCUTS)throw new IllegalArgumentException("You can save up to eight shortcuts.");
                android.content.SharedPreferences prefs=host.getSharedPreferences("ui",Context.MODE_PRIVATE);
                controlSerialized=ShortcutBindings.normalizeControls(prefs.getString("controlShortcuts",""));
                prefs.edit().putString("shortcuts",ShortcutMatcher.normalize(String.join(";",values))).putString("controlShortcuts",controlSerialized).apply();
            }else controlSerialized=bindings.put(actionId,keys,replacingChord);
        }catch(IllegalArgumentException conflict){captureState=CaptureState.INVALID;renderAction();captureLabel.setText(conflict.getMessage()==null?"This overlaps another shortcut. Try a different combination.":conflict.getMessage());return;}
        android.content.SharedPreferences prefs=host.getSharedPreferences("ui",Context.MODE_PRIVATE);
        if(!dashboard())prefs.edit().putString("shortcuts",ShortcutMatcher.normalize(bindings.shortcuts(ShortcutBindings.TOGGLE))).putString("controlShortcuts",controlSerialized).apply();
        finishToIdle();host.shortcutsChanged();renderBindings();renderAction();if(changed!=null)changed.run();
    }
    private void finishCapture(String hint){handler.removeCallbacks(captureTimeout);detachRecorder();held.clear();candidate.clear();captureState=CaptureState.IDLE;replacingChord=null;clearActive();renderAction();if(hint!=null){captureLabel.setText(hint);captureLabel.setVisibility(VISIBLE);}}
    private void finishToIdle(){handler.removeCallbacks(captureTimeout);detachRecorder();held.clear();candidate.clear();captureState=CaptureState.IDLE;replacingChord=null;clearActive();}
    private void detachRecorder(){DeckAccessibilityService service=DeckAccessibilityService.connected;if(service!=null&&recorder!=null&&service.recorder==recorder){service.recorder=null;service.reloadShortcuts();}recorder=null;if(host!=null)host.recordGamepad(false);}
    private void clearActive(){if(host.inlineShortcutEditor==this)host.inlineShortcutEditor=null;}
    private void removeShortcut(String chord){
        ShortcutBindings bindings=model();String controlSerialized;
        if(dashboard()){
            List<String> values=new ArrayList<>(Arrays.asList(ShortcutMatcher.normalize(bindings.shortcuts(actionId)).split(";")));if(values.size()<=1||!values.remove(chord))return;
            String next=ShortcutMatcher.normalize(String.join(";",values));host.getSharedPreferences("ui",Context.MODE_PRIVATE).edit().putString("shortcuts",next).apply();
        }else{
            controlSerialized=bindings.remove(actionId,chord);host.getSharedPreferences("ui",Context.MODE_PRIVATE).edit().putString("controlShortcuts",controlSerialized).apply();
        }
        host.shortcutsChanged();renderBindings();renderAction();if(changed!=null)changed.run();
    }
    private static String join(TreeSet<Integer> keys){List<String> values=new ArrayList<>();for(int key:keys)values.add(Integer.toString(key));return String.join(",",values);}
}
