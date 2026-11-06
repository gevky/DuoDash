package dev.nova.deck.bridge;

import dev.nova.deck.ControlLogic;
import java.util.List;

/** Tracks a temporary focused-task change made for handheld controller navigation. */
final class PanelFocusLease {
    private int previousTaskId=-1;
    private int focusedTaskId=-1;
    private int displayId=-1;

    /** Returns the target task to focus, or -1 when no focus change should be made. */
    int acquire(int currentFocusedTask,int targetTask,int targetDisplay){
        if(currentFocusedTask<0||targetTask<0||targetDisplay<0)return -1;
        if(focusedTaskId>=0){
            // Repeated opens for the same live lease must not replace its original restore target.
            if(focusedTaskId==targetTask&&displayId==targetDisplay&&currentFocusedTask==focusedTaskId)return -1;
            // The user or another system action changed focus. Drop our lease and do not take it back.
            if(currentFocusedTask!=focusedTaskId){clear();return -1;}
            // A different display needs an explicit release before another lease is acquired.
            return -1;
        }
        if(currentFocusedTask==targetTask)return -1;
        previousTaskId=currentFocusedTask;
        focusedTaskId=targetTask;
        displayId=targetDisplay;
        return targetTask;
    }

    /** Root-task list is already in z order; permit home/mirror roots when they are visible. */
    static int targetTask(List<ControlLogic.Task> tasks,int display){
        if(tasks==null||display<0)return -1;
        for(ControlLogic.Task task:tasks)if(task.display==display&&task.visible&&task.id>=0)return task.id;
        return -1;
    }

    /** Returns the saved task only while focus is still on the task this lease selected. */
    int release(int currentFocusedTask){
        int restore=currentFocusedTask==focusedTaskId?previousTaskId:-1;
        clear();
        return restore;
    }

    void clear(){previousTaskId=-1;focusedTaskId=-1;displayId=-1;}
    boolean active(){return focusedTaskId>=0;}
    int targetTaskId(){return focusedTaskId;}
    int displayId(){return displayId;}
}
