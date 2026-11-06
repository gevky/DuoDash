package dev.nova.deck;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small, non-focusable overlay keypad; it never opens an IME or consumes outside touches. */
final class PairingCodePanel extends LinearLayout {
    interface Host {
        void submit(String reply);
        void cancel();
        void dragBy(float dx, float dy);
    }

    private static final int BG=0xff17171a, SURFACE=0xff232329, ELEVATED=0xff2b2b32;
    private static final int TEXT=0xfff1eef4, MUTED=0xffaaa7b1, ACCENT=0xffc6b8d9, WARNING=0xffe4bd82;
    private final PairingCodeEntry entry = new PairingCodeEntry();
    private final Host host;
    private final TextView value, message;
    private final Button codeTab, portTab, pair;
    private boolean working;
    private float lastRawX, lastRawY;

    PairingCodePanel(Context context, Host host) {
        super(context);
        this.host=host;
        setOrientation(VERTICAL);
        setPadding(dp(9),dp(5),dp(9),dp(6));
        setBackground(round(BG,14));
        setElevation(dp(12));
        setFocusable(false);
        setContentDescription("DuoDash pairing code keypad");

        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header,new LayoutParams(-1,dp(34)));
        TextView title=text("DuoDash · Pairing",15,TEXT,true);title.setContentDescription("Drag to move the pairing panel");
        header.addView(title,new LayoutParams(0,-1,1));
        title.setOnTouchListener((view,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){lastRawX=event.getRawX();lastRawY=event.getRawY();return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE){float x=event.getRawX(),y=event.getRawY();host.dragBy(x-lastRawX,y-lastRawY);lastRawX=x;lastRawY=y;return true;}
            return event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL;
        });
        Button close=button("×","Close pairing code entry",false);header.addView(close,new LayoutParams(dp(44),dp(32)));
        close.setOnClickListener(v->{clearSensitive();host.cancel();});

        value=text("Enter the six-digit code",16,TEXT,true);value.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);
        value.setPadding(dp(10),0,dp(10),0);value.setBackground(round(SURFACE,8));
        value.setContentDescription("Pairing code entry");addView(value,new LayoutParams(-1,dp(36)));

        LinearLayout tabs=new LinearLayout(context);tabs.setGravity(Gravity.CENTER_VERTICAL);LayoutParams tabsLp=new LayoutParams(-1,dp(34));tabsLp.topMargin=dp(3);addView(tabs,tabsLp);
        codeTab=button("Code","Enter pairing code",false);portTab=button("Port · optional","Enter pairing port fallback",false);
        LinearLayout.LayoutParams tabLp=new LinearLayout.LayoutParams(0,-1,1);tabLp.rightMargin=dp(5);tabs.addView(codeTab,tabLp);tabs.addView(portTab,new LinearLayout.LayoutParams(0,-1,1));
        codeTab.setOnClickListener(v->{entry.setEditingPort(false);renderEntry();});
        portTab.setOnClickListener(v->{entry.setEditingPort(true);renderEntry();});

        String[][] keys={{"1","2","3"},{"4","5","6"},{"7","8","9"},{"⌫","0","Clear"}};
        LinearLayout keypad=new LinearLayout(context);keypad.setOrientation(VERTICAL);LayoutParams keypadLp=new LayoutParams(-1,-2);keypadLp.topMargin=dp(3);addView(keypad,keypadLp);
        for(String[] row:keys){
            LinearLayout line=new LinearLayout(context);line.setGravity(Gravity.CENTER);LinearLayout.LayoutParams rowLp=new LinearLayout.LayoutParams(-1,dp(40));rowLp.topMargin=dp(2);keypad.addView(line,rowLp);
            for(int i=0;i<row.length;i++){
                String key=row[i];Button digit=button(key,key.equals("⌫")?"Delete last digit":key.equals("Clear")?"Clear current field":"Digit "+key,false);
                LinearLayout.LayoutParams keyLp=new LinearLayout.LayoutParams(0,-1,1);if(i>0)keyLp.leftMargin=dp(5);line.addView(digit,keyLp);
                digit.setOnClickListener(v->{if(working)return;if(key.equals("⌫"))entry.backspace();else if(key.equals("Clear"))entry.clearCurrent();else entry.appendDigit(key.charAt(0));renderEntry();});
            }
        }

        pair=button("Pair this device","Pair this device using the entered code",true);LayoutParams pairLp=new LayoutParams(-1,dp(42));pairLp.topMargin=dp(4);addView(pair,pairLp);
        pair.setOnClickListener(v->submit());

        message=text("",11,WARNING,false);
        message.setGravity(Gravity.CENTER);message.setPadding(dp(2),dp(1),dp(2),0);addView(message,new LayoutParams(-1,dp(18)));
        renderEntry();
    }

    void setWorking(boolean value){working=value;pair.setEnabled(!working&&entry.canSubmit());pair.setAlpha(pair.isEnabled()?1f:.52f);if(working){clearSensitive();message.setText("Pairing…");} }
    void showResult(boolean success,String detail){working=false;clearSensitive();message.setText(detail==null||detail.isEmpty()?(success?"Paired successfully.":"Pairing failed. Try again."):detail);message.setTextColor(success?ACCENT:WARNING);renderEntry();}
    void setPanelMessage(String detail){message.setText(detail==null?"":detail);}
    void clearSensitive(){entry.clearAll();renderEntry();}

    private void submit(){
        PairingReplyParser.Reply parsed=PairingReplyParser.parse(entry.reply());
        if(!parsed.valid){message.setText(parsed.error);parsed.clear();return;}
        String reply=entry.reply();
        setWorking(true);
        host.submit(reply);
        // Do not keep the entered code after handing it to the one-shot pairing service.
        parsed.clear();
    }

    private void renderEntry(){
        String code=entry.codeText();String port=entry.portText();
        value.setText(entry.editingPort()?(port.isEmpty()?"Pairing port · optional":port):code.isEmpty()?"Enter the six-digit code":code);
        value.setContentDescription(entry.editingPort()?"Optional pairing port, "+(port.isEmpty()?"empty":port):"Pairing code, "+(code.isEmpty()?"empty":code));
        codeTab.setBackground(round(entry.editingPort()?SURFACE:0xff37313e,8));
        portTab.setBackground(round(entry.editingPort()?0xff37313e:SURFACE,8));
        pair.setEnabled(!working&&entry.canSubmit());pair.setAlpha(pair.isEnabled()?1f:.52f);
    }

    private Button button(String label,String description,boolean primary){
        Button button=new Button(getContext());button.setText(label);button.setAllCaps(false);button.setTextSize(primary?14:12);
        button.setTextColor(TEXT);button.setMinHeight(dp(40));button.setPadding(dp(4),0,dp(4),0);button.setContentDescription(description);
        button.setBackground(round(primary?0xff494052:ELEVATED,8));button.setFocusable(true);return button;
    }
    private TextView text(String value,float size,int color,boolean bold){TextView view=new TextView(getContext());view.setText(value);view.setTextSize(size);view.setTextColor(color);if(bold)view.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));return view;}
    private GradientDrawable round(int color,int radius){GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(radius));return drawable;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
