package dev.nova.deck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Full-display DuoDash settings surface hosted above, and alongside, the dashboard window. */
final class SettingsPageView extends FrameLayout {
    private static final int BASE=0xff101012, RAIL=0xff17171a, SELECTED=0xff29272d;
    private static final int TEXT=0xfff1eff4, MUTED=0xffb8b4be, ACCENT=0xffd2c6df;
    private static final String[] CATEGORIES={"output","dashboard","connection"};
    private static final String[] LABELS={"Display","Dashboard","Connection"};

    private final OverlayService host;
    private final LinearLayout rail,content;
    private final ScrollView scroll;
    private final TextView pageHeading;
    private final Map<String,TextView> categoryViews=new LinkedHashMap<>();
    private final Set<Integer> activationKeys=new HashSet<>(),backKeys=new HashSet<>();
    private String category;

    SettingsPageView(Context context,OverlayService host,String initialCategory){
        super(context);this.host=host;category="controller".equals(initialCategory)?"dashboard":validCategory(initialCategory)?initialCategory:"output";
        setBackgroundColor(BASE);setClickable(true);setFocusable(true);setFocusableInTouchMode(true);setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        LinearLayout page=new LinearLayout(context);page.setOrientation(LinearLayout.VERTICAL);addView(page,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(host.dp(8),0,host.dp(20),0);header.setBackgroundColor(RAIL);page.addView(header,new LinearLayout.LayoutParams(-1,host.dp(56)));
        BackButton back=new BackButton(context);back.setContentDescription("Back to dashboard");back.setFocusable(true);back.setFocusableInTouchMode(false);back.setClickable(true);back.setOnClickListener(v->host.exitSettingsPage());header.addView(back,new LinearLayout.LayoutParams(host.dp(48),host.dp(48)));
        TextView title=new TextView(context);title.setText("Settings");title.setTextSize(20);title.setTextColor(TEXT);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));title.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-1,1);titleParams.leftMargin=host.dp(8);header.addView(title,titleParams);

        LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.HORIZONTAL);page.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        rail=new LinearLayout(context);rail.setOrientation(LinearLayout.VERTICAL);rail.setBackgroundColor(RAIL);rail.setPadding(host.dp(8),host.dp(10),host.dp(8),host.dp(10));body.addView(rail,new LinearLayout.LayoutParams(host.dp(116),-1));
        View separator=new View(context);separator.setBackgroundColor(0xff303034);body.addView(separator,new LinearLayout.LayoutParams(host.dp(1),-1));
        scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(true);scroll.setScrollbarFadingEnabled(false);scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        GradientDrawable scrollbarTrack=new GradientDrawable();scrollbarTrack.setColor(0xff26232a);scrollbarTrack.setSize(host.dp(3),host.dp(1));scroll.setVerticalScrollbarTrackDrawable(scrollbarTrack);
        GradientDrawable scrollbarThumb=new GradientDrawable();scrollbarThumb.setColor(0xffaaa3b2);scrollbarThumb.setCornerRadius(host.dp(2));scrollbarThumb.setSize(host.dp(3),host.dp(28));scroll.setVerticalScrollbarThumbDrawable(scrollbarThumb);
        body.addView(scroll,new LinearLayout.LayoutParams(0,-1,1));
        content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(host.dp(24),host.dp(18),host.dp(24),host.dp(24));scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        pageHeading=new TextView(context);pageHeading.setTextSize(21);pageHeading.setTextColor(TEXT);pageHeading.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));pageHeading.setPadding(0,0,0,host.dp(12));content.addView(pageHeading,new LinearLayout.LayoutParams(-1,host.dp(44)));

        for(int i=0;i<CATEGORIES.length;i++){
            final String id=CATEGORIES[i];TextView item=new TextView(context);item.setText(LABELS[i]);item.setTextSize(13);item.setTextColor(TEXT);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(host.dp(12),0,host.dp(8),0);item.setMinHeight(host.dp(44));item.setFocusable(true);item.setFocusableInTouchMode(false);item.setClickable(true);item.setBackground(ripple(new ColorDrawable(0x00000000)));item.setOnClickListener(v->selectCategory(id));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,host.dp(44));if(i>0)params.topMargin=host.dp(3);rail.addView(item,params);categoryViews.put(id,item);
        }
        renderCategory();
        setAlpha(1f);setTranslationX(0f);
    }

    String category(){return category;}

    void animateIn(){post(()->{setTranslationX(getWidth());animate().translationX(0).setDuration(200).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();});}
    void animateOut(Runnable finished){animate().translationX(getWidth()).setDuration(190).setInterpolator(new android.view.animation.AccelerateInterpolator()).withEndAction(finished).start();}

    void refreshCurrent(){renderCategory();}

    void selectCategory(String next){if(!validCategory(next)||category.equals(next))return;String previous=category;category=next;host.settingsCategoryChanged(previous,next);renderCategory();}

    private boolean validCategory(String value){return Arrays.asList(CATEGORIES).contains(value);}

    private void renderCategory(){
        for(Map.Entry<String,TextView> entry:categoryViews.entrySet()){
            TextView item=entry.getValue();boolean selected=entry.getKey().equals(category);item.setSelected(selected);item.setBackground(categoryBackground());item.setTextColor(selected?ACCENT:TEXT);item.setContentDescription((selected?"Selected, ":"")+LABELS[Arrays.asList(CATEGORIES).indexOf(entry.getKey())]);
        }
        pageHeading.setText(LABELS[Arrays.asList(CATEGORIES).indexOf(category)]);
        content.removeViews(1,Math.max(0,content.getChildCount()-1));
        host.populateSettingsCategory(category,content);
        scroll.scrollTo(0,0);
        TextView selected=categoryViews.get(category);if(selected!=null&&findFocus()==null)selected.requestFocus();
    }

    private Drawable ripple(Drawable base){return new RippleDrawable(ColorStateList.valueOf(0x28ffffff),base,null);}
    private Drawable categoryBackground(){
        StateListDrawable states=new StateListDrawable();android.graphics.drawable.GradientDrawable focused=new android.graphics.drawable.GradientDrawable();focused.setColor(SELECTED);focused.setStroke(Math.max(1,host.dp(1)),ACCENT);
        android.graphics.drawable.GradientDrawable selected=new android.graphics.drawable.GradientDrawable();selected.setColor(SELECTED);
        android.graphics.drawable.GradientDrawable pressed=new android.graphics.drawable.GradientDrawable();pressed.setColor(0xff29272d);
        android.graphics.drawable.GradientDrawable normal=new android.graphics.drawable.GradientDrawable();normal.setColor(0x00000000);
        states.addState(new int[]{android.R.attr.state_focused},focused);states.addState(new int[]{android.R.attr.state_selected},selected);states.addState(new int[]{android.R.attr.state_pressed},pressed);states.addState(new int[]{},normal);return states;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(host.touchOnlyEnabled()&&DashboardInputPolicy.navigationKey(event.getKeyCode())
                &&(event.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD)||event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK))
                &&!(host.inlineShortcutEditor!=null&&host.inlineShortcutEditor.isCapturing()))return true;
        int key=event.getKeyCode();
        if(key==KeyEvent.KEYCODE_BUTTON_B||key==KeyEvent.KEYCODE_BACK){
            if(event.getAction()==KeyEvent.ACTION_DOWN){if(event.getRepeatCount()==0)backKeys.add(key);return true;}
            if(event.getAction()==KeyEvent.ACTION_UP){boolean owned=backKeys.remove(key);if(owned&&!event.isCanceled()){
                if(host.inlineShortcutEditor!=null&&host.inlineShortcutEditor.cancelPending())return true;
                if(key==KeyEvent.KEYCODE_BACK&&imeVisible()&&findFocus() instanceof android.widget.EditText){
                    InputMethodManager input=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);if(input!=null)input.hideSoftInputFromWindow(getWindowToken(),0);
                }else host.exitSettingsPage();
            }return true;}
            return true;
        }
        if(key==KeyEvent.KEYCODE_BUTTON_A||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){
            if(host.inlineShortcutEditor!=null&&host.inlineShortcutEditor.isCapturing())return super.dispatchKeyEvent(event);
            if(event.getAction()==KeyEvent.ACTION_DOWN){if(event.getRepeatCount()==0)activationKeys.add(key);return true;}
            if(event.getAction()==KeyEvent.ACTION_UP){boolean owned=activationKeys.remove(key);View focused=findFocus();if(owned&&!event.isCanceled()&&focused!=null&&focused.isShown()&&focused.isClickable()&&focused.isEnabled())focused.performClick();return true;}
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean imeVisible(){
        if(android.os.Build.VERSION.SDK_INT>=30){WindowInsets insets=getRootWindowInsets();return insets!=null&&insets.isVisible(WindowInsets.Type.ime());}
        return false;
    }

    private static final class BackButton extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        BackButton(Context context){super(context);setFocusable(true);setFocusableInTouchMode(false);setClickable(true);StateListDrawable states=new StateListDrawable();android.graphics.drawable.GradientDrawable focused=new android.graphics.drawable.GradientDrawable();focused.setColor(0xff29272d);focused.setStroke(Math.max(1,Math.round(getResources().getDisplayMetrics().density)),ACCENT);android.graphics.drawable.GradientDrawable pressed=new android.graphics.drawable.GradientDrawable();pressed.setColor(0xff29272d);android.graphics.drawable.GradientDrawable normal=new android.graphics.drawable.GradientDrawable();normal.setColor(0x00000000);states.addState(new int[]{android.R.attr.state_focused},focused);states.addState(new int[]{android.R.attr.state_pressed},pressed);states.addState(new int[]{},normal);setBackground(states);paint.setColor(ACCENT);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(getResources().getDisplayMetrics().density*1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float d=getResources().getDisplayMetrics().density,cx=getWidth()/2f,cy=getHeight()/2f;Path path=new Path();path.moveTo(cx+d*5,cy);path.lineTo(cx-d*4,cy);path.moveTo(cx-d*4,cy);path.lineTo(cx+d,cy-d*5);path.moveTo(cx-d*4,cy);path.lineTo(cx+d,cy+d*5);canvas.drawPath(path,paint);}
    }
}
