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
import java.util.TreeSet;

/** Compact in-place shortcut list and capture flow for Settings. */
final class InlineShortcutEditor extends LinearLayout {
    private enum CaptureState { IDLE, CAPTURING, READY, TOO_MANY, INVALID }
    private static final long CAPTURE_LIMIT_MS=20_000L;
    private static final int MAX_SHORTCUTS=8;

    private final OverlayService host;
    private final DeckController controller;
    private final LinearLayout bindings;
    private final TextView note,captureLabel;
    private final Button action;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable captureTimeout=()->finishCapture("Recording timed out. Try again.");
    private final TreeSet<Integer> held=new TreeSet<>(),candidate=new TreeSet<>();
    private DeckAccessibilityService.Recorder recorder;
    private CaptureState captureState=CaptureState.IDLE;
    private boolean disposed;

    InlineShortcutEditor(Context context,OverlayService host,DeckController controller){
        super(context);
        this.host=host;this.controller=controller;
        setOrientation(VERTICAL);
        setPadding(host.dp(4),host.dp(2),host.dp(4),host.dp(2));
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

        note=new TextView(context);note.setTextSize(12);note.setTextColor(0xffb8b4be);
        note.setText("Add one to three buttons. A shortcut opens and closes DuoDash.");
        addView(note,new LinearLayout.LayoutParams(-1,-2));

        bindings=new LinearLayout(context);bindings.setOrientation(VERTICAL);
        LinearLayout.LayoutParams listParams=new LinearLayout.LayoutParams(-1,-2);listParams.topMargin=host.dp(4);
        addView(bindings,listParams);

        captureLabel=new TextView(context);captureLabel.setTextSize(12);captureLabel.setTextColor(0xffb8b4be);
        captureLabel.setGravity(Gravity.CENTER_VERTICAL);captureLabel.setVisibility(GONE);
        LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(-1,-2);labelParams.topMargin=host.dp(4);
        addView(captureLabel,labelParams);

        action=new Button(context);action.setAllCaps(false);action.setTextSize(14);action.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xffa6a2ad,0xfff1eff4}));action.setBackgroundTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_pressed},new int[]{android.R.attr.state_focused},new int[]{}},new int[]{0xff514b58,0xff423d47,0xff302d33}));action.setMinHeight(host.dp(44));
        action.setOnClickListener(v->performAction());
        LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-1,host.dp(48));actionParams.topMargin=host.dp(2);
        addView(action,actionParams);
        renderBindings();renderAction();
    }

    /** True while a capture or captured chord is pending. */
    boolean isRecording(){return captureState!=CaptureState.IDLE;}
    boolean isCapturing(){return captureState==CaptureState.CAPTURING;}

    /** Lets the Settings host consume Back once to cancel a pending capture before closing the panel. */
    boolean cancelPending(){
        if(captureState==CaptureState.IDLE)return false;
        finishCapture(null);return true;
    }

    /** Stops both recorder paths when the Settings category is hidden or removed. */
    public void dispose(){
        if(disposed)return;disposed=true;handler.removeCallbacks(captureTimeout);detachRecorder();
        held.clear();candidate.clear();captureState=CaptureState.IDLE;
    }

    private String saved(){
        return ShortcutMatcher.normalize(host.getSharedPreferences("ui",Context.MODE_PRIVATE).getString("shortcuts",""));
    }

    private void renderBindings(){
        bindings.removeAllViews();String value=saved();
        if(value.isEmpty()){
            TextView empty=new TextView(getContext());empty.setText("No shortcuts recorded");empty.setTextSize(13);empty.setTextColor(0xffb8b4be);
            empty.setGravity(Gravity.CENTER_VERTICAL);bindings.addView(empty,new LinearLayout.LayoutParams(-1,host.dp(40)));
            return;
        }
        List<String> chords=new ArrayList<>(Arrays.asList(value.split(";")));
        for(String chord:chords){
            String label=ShortcutDialogs.label(chord);
            LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name=new TextView(getContext());name.setText(label);name.setTextSize(14);name.setTextColor(0xfff1eff4);
            name.setGravity(Gravity.CENTER_VERTICAL);name.setSingleLine(true);name.setContentDescription(label);
            row.addView(name,new LinearLayout.LayoutParams(0,host.dp(48),1));
            TextView remove=new TextView(getContext());remove.setText("×");remove.setTextSize(19);remove.setTextColor(0xffd2c6df);
            remove.setGravity(Gravity.CENTER);remove.setMinWidth(host.dp(44));remove.setMinHeight(host.dp(44));
            remove.setFocusable(true);remove.setFocusableInTouchMode(false);remove.setClickable(true);
            StateListDrawable removeStates=new StateListDrawable();GradientDrawable focused=new GradientDrawable();focused.setColor(0xff29272d);focused.setStroke(Math.max(1,host.dp(1)),0xffd2c6df);GradientDrawable pressed=new GradientDrawable();pressed.setColor(0xff29272d);GradientDrawable normal=new GradientDrawable();normal.setColor(0x00000000);removeStates.addState(new int[]{android.R.attr.state_focused},focused);removeStates.addState(new int[]{android.R.attr.state_pressed},pressed);removeStates.addState(new int[]{},normal);remove.setBackground(removeStates);
            remove.setContentDescription(chords.size()==1?"At least one shortcut is required":"Remove "+label);
            remove.setEnabled(chords.size()>1);remove.setAlpha(remove.isEnabled()?1f:.45f);
            remove.setOnClickListener(v->{if(chords.size()>1)removeShortcut(chord);});
            row.addView(remove,new LinearLayout.LayoutParams(host.dp(44),host.dp(44)));
            bindings.addView(row,new LinearLayout.LayoutParams(-1,host.dp(48)));
        }
    }

    private void renderAction(){
        if(disposed)return;
        boolean connected=DeckAccessibilityService.connected!=null&&controller.state.bridgeReady;
        int count=saved().isEmpty()?0:saved().split(";").length;
        note.setText(connected?"Add one to three buttons. A shortcut opens and closes DuoDash."
            :"Connect system controls and enable always-visible controls to record shortcuts.");
        captureLabel.setVisibility(captureState==CaptureState.IDLE?GONE:VISIBLE);
        action.setEnabled(true);
        switch(captureState){
            case IDLE:
                action.setText("Record shortcut");action.setEnabled(connected&&count<MAX_SHORTCUTS);
                if(count>=MAX_SHORTCUTS)note.setText("You can save up to eight shortcuts. Remove one to add another.");
                if(!connected)note.setText("Connect system controls and enable always-visible controls to record shortcuts.");
                break;
            case CAPTURING:
                action.setText("Cancel");
                if(candidate.isEmpty())captureLabel.setText("Press one to three buttons, then release them.");
                else captureLabel.setText("Holding: "+ShortcutDialogs.label(join(candidate))+(candidate.size()>3?" · use no more than three.":""));
                break;
            case READY:
                action.setText("Save shortcut");captureLabel.setText(ShortcutDialogs.label(join(candidate))+" · press A to save or B to discard.");
                break;
            case TOO_MANY:
                action.setText("Try again");captureLabel.setText("Choose no more than three buttons.");
                break;
            case INVALID:
                action.setText("Try again");
                break;
        }
    }

    private void performAction(){
        if(disposed)return;
        switch(captureState){
            case IDLE:beginCapture();break;
            case CAPTURING:finishCapture(null);break;
            case READY:saveCandidate();break;
            case TOO_MANY:case INVALID:beginCapture();break;
        }
    }

    private void beginCapture(){
        if(disposed)return;
        DeckAccessibilityService service=DeckAccessibilityService.connected;
        if(service==null||!controller.state.bridgeReady){captureLabel.setText("Connect system controls and enable always-visible controls first.");captureLabel.setVisibility(VISIBLE);return;}
        if(!ShortcutMatcher.normalize(saved()).isEmpty()&&saved().split(";").length>=MAX_SHORTCUTS){renderAction();return;}
        held.clear();candidate.clear();captureState=CaptureState.CAPTURING;
        recorder=this::onRecordedEvent;service.recorder=recorder;
        host.recordGamepad(true);handler.removeCallbacks(captureTimeout);handler.postDelayed(captureTimeout,CAPTURE_LIMIT_MS);
        renderAction();
    }

    private void onRecordedEvent(KeyEvent event){
        if(disposed||event==null)return;
        int code=event.getKeyCode();int actionCode=event.getAction();
        if(code==KeyEvent.KEYCODE_HOME||code==KeyEvent.KEYCODE_POWER||code==KeyEvent.KEYCODE_UNKNOWN)return;
        if(captureState!=CaptureState.CAPTURING)return;
        if(actionCode==KeyEvent.ACTION_DOWN){
            if(event.getRepeatCount()>0)return;
            held.add(code);candidate.add(code);
            if(candidate.size()>3)captureLabel.setText("Holding: "+ShortcutDialogs.label(join(candidate))+" · use no more than three.");
        }else if(actionCode==KeyEvent.ACTION_UP){
            held.remove(code);
            if(held.isEmpty()&&!candidate.isEmpty())candidateReleased();
        }
        if(captureState==CaptureState.CAPTURING)renderAction();
    }

    private void candidateReleased(){
        handler.removeCallbacks(captureTimeout);
        detachRecorder();
        captureState=candidate.size()<=3?CaptureState.READY:CaptureState.TOO_MANY;
        renderAction();action.requestFocus();
    }

    private void saveCandidate(){
        if(captureState!=CaptureState.READY||candidate.isEmpty())return;
        String existing=saved();int[] keys=candidate.stream().mapToInt(Integer::intValue).toArray();
        if(!ShortcutMatcher.canAdd(existing,keys)){
            captureState=CaptureState.INVALID;renderAction();
            captureLabel.setText("This overlaps an existing shortcut. Press B to discard or try again.");return;
        }
        String chord=join(candidate);String next=ShortcutMatcher.normalize(existing.isEmpty()?chord:existing+";"+chord);
        if(next.isEmpty()){captureState=CaptureState.INVALID;renderAction();captureLabel.setText("That button combination cannot be saved. Try again.");return;}
        host.getSharedPreferences("ui",Context.MODE_PRIVATE).edit().putString("shortcuts",next).apply();
        DeckAccessibilityService service=DeckAccessibilityService.connected;if(service!=null)service.reloadShortcuts();
        host.shortcutsChanged();detachRecorder();held.clear();candidate.clear();captureState=CaptureState.IDLE;
        renderBindings();renderAction();
    }

    private void finishCapture(String hint){
        handler.removeCallbacks(captureTimeout);detachRecorder();held.clear();candidate.clear();captureState=CaptureState.IDLE;
        renderAction();
        if(hint!=null){captureLabel.setText(hint);captureLabel.setVisibility(VISIBLE);}
    }

    private void detachRecorder(){
        DeckAccessibilityService service=DeckAccessibilityService.connected;
        if(service!=null&&recorder!=null&&service.recorder==recorder)service.recorder=null;
        recorder=null;
        if(host!=null)host.recordGamepad(false);
    }

    private void removeShortcut(String chord){
        String existing=saved();List<String> values=new ArrayList<>(Arrays.asList(existing.split(";")));
        if(values.size()<=1||!values.remove(chord))return;
        String next=ShortcutMatcher.normalize(String.join(";",values));
        host.getSharedPreferences("ui",Context.MODE_PRIVATE).edit().putString("shortcuts",next).apply();
        DeckAccessibilityService service=DeckAccessibilityService.connected;if(service!=null)service.reloadShortcuts();
        host.shortcutsChanged();renderBindings();renderAction();
    }

    private static String join(TreeSet<Integer> keys){
        List<String> values=new ArrayList<>();for(int key:keys)values.add(Integer.toString(key));return String.join(",",values);
    }
}
