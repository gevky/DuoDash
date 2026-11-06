package dev.nova.deck;

import java.util.Locale;

/** Suggested names for newly discovered external displays. Saved names are handled separately. */
final class OutputNamePolicy {
    static String suggestion(String product,String name){
        String description=(product==null?"":product)+" "+(name==null?"":name);
        String lower=description.toLowerCase(Locale.ROOT);
        if(lower.contains("lontium")||lower.contains("rds"))return "Retroid Addon";
        if(lower.contains("xreal")||lower.contains("nreal"))return "XREAL glasses";
        String displayName=name==null||name.isEmpty()?"External display":name;
        return displayName.equals("DP Screen")?"External display":displayName;
    }

    private OutputNamePolicy(){}
}
