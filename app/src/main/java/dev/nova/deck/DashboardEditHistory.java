package dev.nova.deck;

import java.util.ArrayDeque;
import java.util.Deque;

/** Bounded, in-memory undo history for one dashboard edit session. */
final class DashboardEditHistory {
    static final int LIMIT=20;
    private final Deque<DashboardEditSnapshot> previous=new ArrayDeque<>();
    private DashboardEditSnapshot observed;

    void begin(DashboardEditSnapshot initial){clear();observed=initial;}
    boolean observe(DashboardEditSnapshot next){
        if(next==null)return false;
        if(observed==null){observed=next;return false;}
        if(observed.equals(next))return false;
        previous.addLast(observed);while(previous.size()>LIMIT)previous.removeFirst();observed=next;return true;
    }
    boolean canUndo(){return !previous.isEmpty();}
    DashboardEditSnapshot undo(){if(previous.isEmpty())return null;observed=previous.removeLast();return observed;}
    int size(){return previous.size();}
    void clear(){previous.clear();observed=null;}
}
