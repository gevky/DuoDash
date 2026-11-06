package dev.nova.deck;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Three-step first-run and recovery flow, rendered from live setup readiness. */
final class SetupWizardView extends LinearLayout {
    interface Actions {
        void overlayPermission();
        void accessibilitySettings();
        void notificationPermission();
        void notificationSettings();
        void pairThisDevice();
        void pairAgain();
        void reconnect();
        void developerOptions();
        void aboutDevice();
        void appInfo();
        void beginShortcutCapture(SetupShortcutCapture.Listener listener);
        void endShortcutCapture();
        String savedShortcuts();
        void saveShortcut(String chord);
        void refresh();
        void next();
        void back();
        void finish();
    }

    private static final int BG=0xff101012, SURFACE=0xff1c1c20, ELEVATED=0xff27262b;
    private static final int TEXT=0xfff0edf3, MUTED=0xffaaa6b0, ACCENT=0xffd2c6df, WARNING=0xffe4bd82;
    private final Actions actions;
    private final LinearLayout page;
    private final LinearLayout statusRows;
    private final TextView stepLabel,title,description,feedback,primary;
    private final Button backButton;
    private final View[] progress=new View[3];
    private int step;
    private boolean busy;
    private boolean restrictedHelpExpanded;
    private boolean pairAgainHelpExpanded;
    private boolean pairingPortHelpExpanded;
    private boolean usbRescueExpanded;
    private boolean recordingShortcut;
    private String captureMessage="";
    private final TreeSet<Integer> heldShortcutKeys=new TreeSet<>();
    private final TreeSet<Integer> candidateShortcutKeys=new TreeSet<>();
    private SetupReadiness.Status status;

    SetupWizardView(Context context,Actions actions){
        super(context);this.actions=actions;setOrientation(VERTICAL);setBackgroundColor(BG);
        int pad=dp(24);setPadding(pad,dp(18),pad,dp(18));
        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);addView(header,new LayoutParams(-1,dp(48)));
        ImageView mark=new ImageView(context);mark.setImageResource(R.drawable.ic_nova);mark.setContentDescription("DuoDash");header.addView(mark,new LayoutParams(dp(30),dp(30)));
        TextView brand=text("DuoDash",18,TEXT,true);LinearLayout.LayoutParams brandParams=new LayoutParams(0,-1,1);brandParams.leftMargin=dp(10);header.addView(brand,brandParams);
        stepLabel=text("STEP 1 OF 3",11,MUTED,true);header.addView(stepLabel,new LayoutParams(-2,-2));
        LinearLayout bars=new LinearLayout(context);bars.setOrientation(HORIZONTAL);LayoutParams barsParams=new LayoutParams(-1,dp(3));barsParams.topMargin=dp(14);addView(bars,barsParams);
        for(int i=0;i<3;i++){View bar=new View(context);GradientDrawable shape=new GradientDrawable();shape.setColor(SURFACE);shape.setCornerRadius(dp(2));bar.setBackground(shape);LayoutParams lp=new LayoutParams(0,-1,1);if(i>0)lp.leftMargin=dp(6);bars.addView(bar,lp);progress[i]=bar;}
        ScrollView scroll=new ScrollView(context);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);LayoutParams scrollParams=new LayoutParams(-1,0,1);scrollParams.topMargin=dp(18);addView(scroll,scrollParams);
        page=new LinearLayout(context);page.setOrientation(VERTICAL);page.setPadding(0,0,0,dp(12));scroll.addView(page,new ScrollView.LayoutParams(-1,-2));
        title=text("",26,TEXT,true);title.setLetterSpacing(-.02f);page.addView(title,new LayoutParams(-1,-2));
        description=text("",14,MUTED,false);description.setPadding(0,dp(8),0,dp(12));description.setLineSpacing(dp(2),1f);page.addView(description,new LayoutParams(-1,-2));
        statusRows=new LinearLayout(context);statusRows.setOrientation(VERTICAL);page.addView(statusRows,new LayoutParams(-1,-2));
        feedback=text("",13,WARNING,false);feedback.setPadding(0,dp(8),0,0);feedback.setLineSpacing(dp(2),1f);feedback.setVisibility(GONE);page.addView(feedback,new LayoutParams(-1,-2));
        LinearLayout footer=new LinearLayout(context);footer.setGravity(Gravity.CENTER_VERTICAL);LayoutParams footerParams=new LayoutParams(-1,dp(52));footerParams.topMargin=dp(14);addView(footer,footerParams);
        backButton=button("Back",false);footer.addView(backButton,new LayoutParams(dp(92),dp(48)));backButton.setOnClickListener(v->actions.back());
        primary=button("Continue",true);LayoutParams primaryParams=new LayoutParams(0,dp(48),1);primaryParams.leftMargin=dp(10);footer.addView(primary,primaryParams);primary.setOnClickListener(v->primaryAction());
        render(null,0,"",false);
    }

    void render(SetupReadiness.Status next,int currentStep,String message,boolean operationBusy){
        status=next;step=Math.max(0,Math.min(2,currentStep));busy=operationBusy;
        backButton.setVisibility(step==0?GONE:VISIBLE);
        stepLabel.setText("STEP "+(step+1)+" OF 3");for(int i=0;i<3;i++){GradientDrawable shape=new GradientDrawable();shape.setColor(i<=step?ACCENT:SURFACE);shape.setCornerRadius(dp(2));progress[i].setBackground(shape);}
        statusRows.removeAllViews();
        if(step==0)renderPermissions();else if(step==1)renderPairing();else renderShortcut();
        feedback.setText(message==null?"":message);feedback.setVisibility(message==null||message.isEmpty()?GONE:VISIBLE);
        primary.setEnabled(!busy&&primaryEnabled());primary.setAlpha(primary.isEnabled()?1f:.55f);primary.setText(primaryTitle());
    }

    void stopCapture(){stopShortcutCapture();}

    private void renderPermissions(){
        title.setText("Give DuoDash access");
        description.setText("Display over other apps lets the dashboard float above your game. Accessibility keeps DuoDash above Android’s status bar and handles your button shortcut.");
        boolean overlay=status!=null&&status.overlayAllowed;
        boolean accessEnabled=status!=null&&status.accessibilityEnabled;
        boolean accessBound=status!=null&&status.accessibilityBound;
        row("Display over other apps","Lets the dashboard appear above games and other apps.",overlay?"Allowed":"Required",overlay,overlay?null:"Open",overlay?null:()->actions.overlayPermission());
        String accessibilityDetail=accessBound
            ?"Keeps DuoDash above Android’s status bar and handles your button shortcut."
            :accessEnabled?"Enabled in Android; waiting for DuoDash’s status-bar controls and shortcut to connect."
            :"Keeps DuoDash above Android’s status bar and handles your button shortcut.";
        row("Accessibility service",accessibilityDetail,accessBound?"Connected":accessEnabled?"Connecting":"Required",accessBound,accessBound?null:"Open",accessBound?null:()->actions.accessibilitySettings());
        if(Build.VERSION.SDK_INT>=33&&!accessBound){
            TextView help=text(restrictedHelpExpanded?"Android says restricted? Hide details":"Android says restricted?",12,ACCENT,true);help.setPadding(dp(4),dp(9),dp(4),dp(8));help.setFocusable(true);help.setClickable(true);help.setContentDescription("Help with Android restricted settings");statusRows.addView(help,new LayoutParams(-1,dp(40)));
            help.setOnClickListener(v->{restrictedHelpExpanded=!restrictedHelpExpanded;render(status,step,"",busy);});
            if(restrictedHelpExpanded){TextView explanation=text("For a sideloaded app, open DuoDash app info, use the three-dot menu to allow restricted settings, then return to Accessibility and enable DuoDash.",12,MUTED,false);explanation.setLineSpacing(dp(2),1f);explanation.setPadding(dp(4),0,dp(4),dp(8));statusRows.addView(explanation,new LayoutParams(-1,-2));Button appInfo=button("Open app info",false);statusRows.addView(appInfo,new LayoutParams(-1,dp(44)));appInfo.setOnClickListener(v->actions.appInfo());}
        }
    }

    private void renderPairing(){
        boolean paired=status!=null&&status.paired;
        boolean live=paired&&status.liveHelperReady;
        title.setText(live?"Device connected":paired?"Reconnect this device":"Connect this device");
        description.setText(paired?
            "DuoDash has saved this device’s pairing. Reconnect when Wireless debugging is available; no new pairing code is needed.":
            "1. Tap Start pairing to open Developer options.\n2. Turn on Wireless debugging, then tap Pair device with pairing code.\n3. Keep that dialog open and enter its code on the DuoDash keypad.");
        row("Device pairing",paired?"Saved on this device":"Not paired yet",paired?"Saved":"Required",paired,null,()->{});
        if(paired){
            row("System controls",live?"Connected":"Waiting for system controls",live?"Connected":"Waiting",live,null,null);
            if(!live){smallAction("Open Developer options",()->actions.developerOptions());pairAgainHelp(status);}
        }else{
            boolean devOptions=developerOptionsEnabled();
            if(!devOptions){
                TextView help=text("1. Open About phone/device → Build number and tap it seven times.\n2. Return here and tap Start pairing.",12,MUTED,false);
                help.setLineSpacing(dp(2),1f);help.setPadding(dp(2),dp(2),dp(2),dp(6));statusRows.addView(help,new LayoutParams(-1,-2));
                row("Developer options","Needed once before Wireless debugging can be opened.","Needed",false,"Open",()->actions.aboutDevice());
            }
            boolean canPair=devOptions&&status!=null&&status.canStartPairing();
            row("Wireless debugging",canPair?"Turn it on, then choose Pair device with pairing code. Keep the dialog open while you enter its code.":devOptions?"Available when you start pairing.":"Available after Developer options are enabled.",canPair?"Ready":devOptions?"Waiting":"Waiting",canPair,null,null);
            TextView portHelp=text(pairingPortHelpExpanded?"If automatic discovery fails, switch the keypad to Port and enter the pairing port shown in Android. The code and port are used only for this attempt.":"Pairing service not found?",12,pairingPortHelpExpanded?MUTED:ACCENT,pairingPortHelpExpanded);
            portHelp.setPadding(dp(4),dp(9),dp(4),dp(8));portHelp.setFocusable(true);portHelp.setClickable(true);statusRows.addView(portHelp,new LayoutParams(-1,-2));
            portHelp.setOnClickListener(v->{pairingPortHelpExpanded=!pairingPortHelpExpanded;render(status,step,"",busy);});
            boolean fallbackReady=status!=null&&status.canUsePairingNotification();
            boolean notificationPermission=status!=null&&status.notificationsPermissionGranted;
            row("Pairing fallback","For the pairing fallback.",fallbackReady?"Available":"Needed",fallbackReady,
                fallbackReady?null:notificationPermission?"Settings":"Allow",
                fallbackReady?null:()->{if(notificationPermission)actions.notificationSettings();else actions.notificationPermission();});
        }
        if(paired&&!status.liveHelperReady)usbRescueHelp();
    }

    private void usbRescueHelp(){
        TextView disclosure=text(usbRescueExpanded?"USB rescue with a computer · Hide":"USB rescue with an authorized computer",12,ACCENT,true);
        disclosure.setPadding(dp(4),dp(9),dp(4),dp(8));disclosure.setFocusable(true);disclosure.setClickable(true);statusRows.addView(disclosure,new LayoutParams(-1,dp(40)));
        disclosure.setOnClickListener(v->{usbRescueExpanded=!usbRescueExpanded;render(status,step,"",busy);});
        if(usbRescueExpanded){
            TextView note=text("For a device that already has a saved DuoDash pairing: connect USB to a computer authorized for USB debugging, then run the rescue script from the DuoDash project folder. It starts the helper for this session and keeps your saved wireless pairing; it does not make USB a permanent replacement. Return here and tap Reconnect system controls.",12,MUTED,false);
            note.setLineSpacing(dp(2),1f);note.setPadding(dp(4),0,dp(4),dp(6));statusRows.addView(note,new LayoutParams(-1,-2));
            TextView command=text("python3 scripts/usb-rescue.py --device SERIAL",12,TEXT,true);command.setTextIsSelectable(true);command.setPadding(dp(8),dp(8),dp(8),dp(8));command.setBackground(round(ELEVATED,8));statusRows.addView(command,new LayoutParams(-1,-2));
            Button copy=button("Copy command",false);LayoutParams lp=new LayoutParams(-1,dp(44));lp.topMargin=dp(6);statusRows.addView(copy,lp);
            copy.setOnClickListener(v->{ClipboardManager manager=(ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);if(manager!=null){manager.setPrimaryClip(ClipData.newPlainText("DuoDash USB rescue", "python3 scripts/usb-rescue.py --device SERIAL"));Toast.makeText(getContext(),"Command copied. Replace SERIAL with the connected device serial.",Toast.LENGTH_LONG).show();}});
        }
    }

    private void renderShortcut(){
        title.setText("Set a button shortcut");
        description.setText("Choose a controller button or a combination of up to three. Release the buttons, then save. Finish setup here when the live checks are ready.");
        String saved=ShortcutMatcher.normalize(actions.savedShortcuts());
        boolean configured=status!=null&&status.shortcutConfigured&&!saved.isEmpty();
        if(configured){
            String first=saved.split(";")[0];
            row("Dashboard shortcut",ShortcutDialogs.label(first),"Ready",true,null,null);
            if(saved.split(";").length>1){TextView extra=text("+"+(saved.split(";").length-1)+" more saved",12,MUTED,false);extra.setPadding(dp(12),dp(4),0,0);statusRows.addView(extra,new LayoutParams(-1,-2));}
        }else row("Dashboard shortcut","One valid button or combination is required before setup can finish.","Needed",false,null,null);

        if(recordingShortcut){
            TextView capture=text(captureMessage.isEmpty()?"Press one button or hold up to three together.":captureMessage,13,MUTED,false);
            capture.setLineSpacing(dp(2),1f);capture.setPadding(dp(4),dp(8),dp(4),dp(4));statusRows.addView(capture,new LayoutParams(-1,-2));
            boolean candidateReady=heldShortcutKeys.isEmpty()&&!candidateShortcutKeys.isEmpty()&&candidateShortcutKeys.size()<=3;
            boolean hasCandidate=!candidateShortcutKeys.isEmpty();
            String label=hasCandidate?ShortcutDialogs.label(joinKeys(candidateShortcutKeys)):"";
            String candidateText=!hasCandidate?"Press a button":candidateShortcutKeys.size()>3?"Use no more than three buttons":!heldShortcutKeys.isEmpty()?"Holding: "+label:"Captured: "+label;
            TextView candidate=text(candidateText,14,TEXT,true);candidate.setPadding(dp(4),dp(2),0,dp(4));statusRows.addView(candidate,new LayoutParams(-1,-2));
            Button recordOrSave=button(hasCandidate&&candidateShortcutKeys.size()<=3?"Save shortcut":"Cancel recording",hasCandidate&&candidateShortcutKeys.size()<=3);
            boolean canAct=candidateReady||!hasCandidate||candidateShortcutKeys.size()>3;
            recordOrSave.setEnabled(canAct);recordOrSave.setAlpha(canAct?1f:.5f);LayoutParams actionParams=new LayoutParams(-1,dp(44));actionParams.topMargin=dp(2);statusRows.addView(recordOrSave,actionParams);
            recordOrSave.setOnClickListener(v->{
                if(!candidateReady){stopShortcutCapture();return;}
                int[] keys=candidateShortcutKeys.stream().mapToInt(Integer::intValue).toArray();
                if(!ShortcutMatcher.canAdd(actions.savedShortcuts(),keys)){captureMessage="This overlaps a saved shortcut. Record a different button or combination.";render(status,step,"",busy);return;}
                actions.saveShortcut(joinKeys(candidateShortcutKeys));stopShortcutCapture();captureMessage="Shortcut saved.";render(status,step,"",busy);
            });
        }else{
            Button record=button(configured?"Add another shortcut":"Record a button shortcut",false);
            LayoutParams recordParams=new LayoutParams(-1,dp(48));recordParams.topMargin=dp(8);statusRows.addView(record,recordParams);
            record.setOnClickListener(v->startShortcutCapture());
        }
    }

    private void startShortcutCapture(){
        heldShortcutKeys.clear();candidateShortcutKeys.clear();captureMessage="Waiting for the controller…";recordingShortcut=true;render(status,step,"",busy);
        actions.beginShortcutCapture(new SetupShortcutCapture.Listener(){
            @Override public void onReady(){if(recordingShortcut){captureMessage="Press one button or hold up to three together.";render(status,step,"",busy);}}
            @Override public void onKey(int keyCode,boolean down){
                if(!recordingShortcut)return;
                if(keyCode<=0||keyCode==KeyEvent.KEYCODE_HOME||keyCode==KeyEvent.KEYCODE_POWER||keyCode==KeyEvent.KEYCODE_UNKNOWN)return;
                if(down){if(heldShortcutKeys.isEmpty())candidateShortcutKeys.clear();heldShortcutKeys.add(keyCode);candidateShortcutKeys.add(keyCode);}
                else heldShortcutKeys.remove(keyCode);
                if(candidateShortcutKeys.size()>3)captureMessage="Use no more than three buttons.";
                render(status,step,"",busy);
            }
            @Override public void onError(String message){recordingShortcut=false;heldShortcutKeys.clear();candidateShortcutKeys.clear();actions.endShortcutCapture();captureMessage=message==null?"Controller recording is unavailable. Reconnect system controls and try again.":message;render(status,step,"",busy);}
        });
    }

    private void stopShortcutCapture(){if(recordingShortcut){recordingShortcut=false;actions.endShortcutCapture();}heldShortcutKeys.clear();candidateShortcutKeys.clear();render(status,step,"",busy);}
    private static String joinKeys(TreeSet<Integer> keys){List<String> values=new ArrayList<>();for(int key:keys)values.add(Integer.toString(key));return String.join(",",values);}

    private void pairAgainHelp(SetupReadiness.Status current){
        TextView disclosure=text(pairAgainHelpExpanded?"Trouble reconnecting? Hide details":"Trouble reconnecting?",12,ACCENT,true);disclosure.setPadding(dp(4),dp(8),dp(4),dp(6));disclosure.setFocusable(true);disclosure.setClickable(true);statusRows.addView(disclosure,new LayoutParams(-1,dp(38)));
        disclosure.setOnClickListener(v->{pairAgainHelpExpanded=!pairAgainHelpExpanded;render(status,step,"",busy);});
        if(pairAgainHelpExpanded){TextView note=text("If you removed DuoDash from Android’s paired devices, pair it again with a new code.",12,MUTED,false);note.setLineSpacing(dp(2),1f);note.setPadding(dp(4),0,dp(4),dp(6));statusRows.addView(note,new LayoutParams(-1,-2));Button pairAgain=button("Use a new pairing code",false);statusRows.addView(pairAgain,new LayoutParams(-1,dp(44)));pairAgain.setOnClickListener(v->actions.pairAgain());}
    }

    private void row(String heading,String detail,String stateText,boolean complete,String buttonText,Runnable action){
        LinearLayout row=new LinearLayout(getContext());row.setOrientation(HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(8),dp(8),dp(8));row.setBackground(round(SURFACE,10));
        LayoutParams params=new LayoutParams(-1,-2);params.topMargin=dp(6);statusRows.addView(row,params);
        LinearLayout copy=new LinearLayout(getContext());copy.setOrientation(VERTICAL);copy.setGravity(Gravity.CENTER_VERTICAL);LayoutParams copyParams=new LayoutParams(0,-2,1);copyParams.rightMargin=dp(8);row.addView(copy,copyParams);
        TextView title=text(heading,14,TEXT,true);copy.addView(title,new LayoutParams(-1,-2));TextView subtitle=text(detail,12,MUTED,false);subtitle.setLineSpacing(dp(1),1f);copy.addView(subtitle,new LayoutParams(-1,-2));
        if(action!=null){Button button=button(buttonText==null?stateText:buttonText,false);row.addView(button,new LayoutParams(dp(88),dp(44)));button.setOnClickListener(v->action.run());}
        else{TextView stateTextView=text(stateText,11,complete?ACCENT:WARNING,true);stateTextView.setGravity(Gravity.CENTER);stateTextView.setPadding(dp(8),0,dp(4),0);row.addView(stateTextView,new LayoutParams(-2,dp(40)));}
    }

    private void primaryAction(){
        if(busy)return;
        if(step==0){if(status==null){actions.refresh();return;}if(!status.overlayAllowed){actions.overlayPermission();return;}if(!status.accessibilityEnabled||!status.accessibilityBound){actions.accessibilitySettings();return;}actions.next();}
        else if(step==1){if(status!=null&&status.paired&&status.liveHelperReady)actions.next();else if(status!=null&&status.paired)actions.reconnect();else if(status==null){actions.refresh();}else if(!status.notificationsPermissionGranted){actions.notificationPermission();}else if(!developerOptionsEnabled())actions.aboutDevice();else actions.pairThisDevice();}
        else if(status==null)actions.refresh();
        else if(!status.shortcutConfigured&&status.paired&&!status.liveHelperReady)actions.reconnect();
        else if(status.canFinish())actions.finish();
        else if(status.paired)actions.reconnect();
        else actions.pairThisDevice();
    }
    private boolean primaryEnabled(){if(step==0)return status!=null;if(step==1)return status!=null;if(status==null)return false;if(!status.shortcutConfigured)return status.paired&&!status.liveHelperReady;return status.canFinish()||status.paired||status.needsPairing();}
    private String primaryTitle(){
        if(busy)return "Checking…";
        if(step==0){if(status==null)return "Checking permissions…";if(!status.overlayAllowed)return "Allow display over apps";if(!status.accessibilityEnabled||!status.accessibilityBound)return "Open Accessibility";return "Continue";}
        if(step==1){if(status==null)return "Check pairing";if(status.paired&&status.liveHelperReady)return "Continue";if(status.paired)return "Reconnect system controls";if(!status.notificationsPermissionGranted)return "Allow notifications";if(!developerOptionsEnabled())return "Open About device";return status.canStartPairing()?"Start pairing":"Pair this device";}
        if(status!=null&&status.canFinish())return "Finish setup";
        if(status!=null&&!status.shortcutConfigured&&status.paired&&!status.liveHelperReady)return "Reconnect system controls";
        if(status!=null&&!status.shortcutConfigured)return "Record a button shortcut first";
        if(status!=null&&status.paired)return "Reconnect system controls";
        return status!=null&&status.needsPairing()?"Pair this device":"Finish setup";
    }

    private boolean developerOptionsEnabled(){
        try{return android.provider.Settings.Global.getInt(getContext().getContentResolver(),android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)==1;}
        catch(Exception ignored){return false;}
    }

    private void smallAction(String label,Runnable action){
        Button button=button(label,false);LayoutParams params=new LayoutParams(-1,dp(44));params.topMargin=dp(8);statusRows.addView(button,params);button.setOnClickListener(v->action.run());
    }

    private Button button(String label,boolean filled){Button button=new Button(getContext());button.setText(label);button.setTextSize(13);button.setAllCaps(false);button.setMinWidth(0);button.setMinimumWidth(0);button.setPadding(dp(8),0,dp(8),0);GradientDrawable bg=round(filled?ACCENT:ELEVATED,10);button.setBackground(bg);button.setTextColor(filled?BG:TEXT);return button;}
    private TextView text(String value,int size,int color,boolean medium){TextView text=new TextView(getContext());text.setText(value);text.setTextSize(size);text.setTextColor(color);text.setTypeface(Typeface.create(medium?"sans-serif-medium":"sans-serif",Typeface.NORMAL));text.setIncludeFontPadding(false);return text;}
    private GradientDrawable round(int color,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));return shape;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
