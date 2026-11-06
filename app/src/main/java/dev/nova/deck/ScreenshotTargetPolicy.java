package dev.nova.deck;

import java.util.Locale;

/** Pure policy for choosing which Android displays a screenshot action captures. */
final class ScreenshotTargetPolicy {
    static final String MAIN="main", EXTERNAL="external", BOTH="both";

    private ScreenshotTargetPolicy(){}

    static String normalize(String value){
        if(value==null)return MAIN;
        switch(value.trim().toLowerCase(Locale.ROOT)){
            case EXTERNAL:return EXTERNAL;
            case BOTH:return BOTH;
            default:return MAIN;
        }
    }

    static String cycle(String value){
        switch(normalize(value)){
            case MAIN:return EXTERNAL;
            case EXTERNAL:return BOTH;
            default:return MAIN;
        }
    }

    static int[] selectDisplayIds(String value,int externalDisplayId){
        switch(normalize(value)){
            case EXTERNAL:
                if(externalDisplayId<=0)throw new IllegalStateException("No external display is connected");
                return new int[]{externalDisplayId};
            case BOTH:
                if(externalDisplayId<=0)throw new IllegalStateException("No external display is connected");
                return new int[]{0,externalDisplayId};
            default:return new int[]{0};
        }
    }
}
