package dev.nova.deck;

/** Mode values offered by the Nova firmware's SystemUI FanTile. */
public final class NativeModes {
    private NativeModes(){}
    public static int[] fanValues(){return new int[]{0,1,4,5,6};}
    public static String[] fanLabels(){return new String[]{"Off","Quiet","Smart","Sport","Custom"};}
    public static boolean validFan(int value){for(int item:fanValues())if(item==value)return true;return false;}
    public static int nextRefresh(int current,int[] supported){
        java.util.TreeSet<Integer> rates=new java.util.TreeSet<>();for(int rate:supported)if(rate>=24&&rate<=240)rates.add(rate);
        if(rates.isEmpty())return 0;Integer next=rates.higher(current);return next==null?rates.first():next;
    }
}
