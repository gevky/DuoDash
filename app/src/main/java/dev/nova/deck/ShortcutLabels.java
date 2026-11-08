package dev.nova.deck;

import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.List;

final class ShortcutLabels {
    static String label(String chord){
        List<String> labels=new ArrayList<>();for(String key:chord.split(","))if(!key.isEmpty())labels.add(KeyEvent.keyCodeToString(Integer.parseInt(key)).replace("KEYCODE_","").replace("BUTTON_","").replace('_',' '));
        return String.join(" + ",labels);
    }
}
