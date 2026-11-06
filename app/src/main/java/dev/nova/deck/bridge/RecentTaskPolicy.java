package dev.nova.deck.bridge;

import java.util.*;

/** Selects ordinary app root tasks without touching launchers or system/infrastructure tasks. */
final class RecentTaskPolicy {
    private static final Set<String> PRESERVED_PACKAGES=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "dev.nova.deck",
            "android",
            "com.android.systemui",
            "com.android.launcher3",
            "com.android.shell",
            "com.rp.gameassistant",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
    )));

    private RecentTaskPolicy() { }

    /** Live external tasks need not have a card in Android's recent-app list. */
    static List<Task> combine(List<Task> recent,List<Task> running){
        Map<Integer,Task> unique=new LinkedHashMap<>();
        if(recent!=null)for(Task task:recent)if(task!=null)unique.put(task.id,task);
        if(running!=null)for(Task task:running)if(task!=null)unique.put(task.id,task);
        return new ArrayList<>(unique.values());
    }

    static List<Task> removable(List<Task> tasks,Set<String> homePackages){
        List<Task> result=new ArrayList<>();if(tasks==null||homePackages==null)return result;
        for(Task task:tasks)if(removable(task,homePackages))result.add(task);
        return result;
    }

    static boolean removable(Task task,Set<String> homePackages){
        if(task==null||task.id<0||task.activityType!=Task.ACTIVITY_TYPE_STANDARD||homePackages==null)return false;
        return ordinaryPackage(task.topPackage,homePackages)&&ordinaryPackage(task.basePackage,homePackages);
    }

    private static boolean ordinaryPackage(String packageName,Set<String> homePackages){
        return packageName!=null&&!packageName.isEmpty()
                &&!PRESERVED_PACKAGES.contains(packageName)
                &&!packageName.startsWith("com.retrohrai.launcher")
                &&!homePackages.contains(packageName);
    }

    static final class Task {
        static final int ACTIVITY_TYPE_STANDARD=1;
        final int id,display,activityType;
        final String topPackage,basePackage;
        Task(int id,int display,int activityType,String topPackage,String basePackage){
            this.id=id;this.display=display;this.activityType=activityType;this.topPackage=topPackage;this.basePackage=basePackage;
        }
    }
}
