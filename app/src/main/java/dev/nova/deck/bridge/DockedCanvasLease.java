package dev.nova.deck.bridge;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A primary-display logical-size lease with an independent device-side recovery watcher. */
final class DockedCanvasLease {
    interface Shell { String run(String... args) throws Exception; }

    private static final String STATE="/data/local/tmp/duodash-docked-canvas-state";
    private static final String PID="/data/local/tmp/duodash-docked-canvas-watcher";
    private static final Pattern OVERRIDE=Pattern.compile("(?m)^Override size: ([0-9]+x[0-9]+)\\s*$");
    private static final String WATCHER=""
        +"while [ -f "+STATE+" ]; do\n"
        +"  set -- $(cat "+STATE+" 2>/dev/null); [ \"$#\" = 6 ] || { sleep 2; continue; };\n"
        +"  mode=$1; bw=$2; bh=$3; old=$4; new=$5; expiry=$6;\n"
        +"  case \"$expiry\" in ''|*[!0-9]*) sleep 2; continue;; esac;\n"
        +"  now=$(awk '{print int($1)}' /proc/uptime);\n"
        +"  if [ \"$now\" -ge \"$expiry\" ]; then\n"
        +"    wmout=$(wm size -d 0 2>/dev/null); case \"$wmout\" in *'Physical size:'*) ;; *) sleep 2; continue;; esac;\n"
        +"    cur=$(printf '%s\\n' \"$wmout\" | sed -n 's/^Override size: \\([0-9][0-9]*x[0-9][0-9]*\\)$/\\1/p');\n"
        +"    if [ \"$cur\" = \"$old\" ] || [ \"$cur\" = \"$new\" ]; then\n"
        +"      if [ \"$mode\" = R ]; then wm size reset -d 0 >/dev/null 2>&1; else wm size \"${bw}x${bh}\" -d 0 >/dev/null 2>&1; fi;\n"
        +"      expected=\"${bw}x${bh}\"; [ \"$mode\" = R ] && expected=;\n"
        +"      wmout2=$(wm size -d 0 2>/dev/null); case \"$wmout2\" in *'Physical size:'*) ;; *) sleep 2; continue;; esac;\n"
        +"      cur2=$(printf '%s\\n' \"$wmout2\" | sed -n 's/^Override size: \\([0-9][0-9]*x[0-9][0-9]*\\)$/\\1/p');\n"
        +"      if [ \"$cur2\" = \"$expected\" ]; then rm -f "+STATE+"; exit; fi;\n"
        +"    else rm -f "+STATE+"; fi;\n"
        +"    sleep 2; continue;\n"
        +"  fi;\n"
        +"  sleep 2;\n"
        +"done\n";

    private final Shell shell;
    private String identity;
    private boolean active;
    private String baselineOverride;
    private String ownedOverride;
    private String previousOwnedOverride;

    DockedCanvasLease(Shell shell) { this.shell=shell; }

    /** Applies the requested logical canvas and returns its current logical dimensions. */
    synchronized int[] apply(String outputIdentity,int logicalWidth,int logicalHeight,int primaryRotation)throws Exception {
        if(outputIdentity==null||outputIdentity.trim().isEmpty())throw new IllegalArgumentException("Output identity is required");
        validate(logicalWidth,logicalHeight);
        if(!active){
            String saved=run("sh","-c","cat "+STATE+" 2>/dev/null || true");
            if(saved!=null&&!saved.trim().isEmpty()){
                readSavedState(saved);
                active=true;
                // A newly created owner cannot establish that a persisted lease belongs to
                // this output. Recover it before taking a fresh baseline.
                release();
            }
        }
        if(active&&!outputIdentity.equals(identity))release();
        if(!active){
            baselineOverride=readOverride();
            previousOwnedOverride=baselineOverride;
            identity=outputIdentity;
            active=true;
        }
        int[] natural=LogicalCanvas.toNaturalSize(logicalWidth,logicalHeight,primaryRotation);
        String target=natural[0]+"x"+natural[1];
        String old=readOverride();
        if(!same(old,ownedOverride)&&!same(old,previousOwnedOverride)){
            clearLease();
            throw new IOException("The primary logical size changed outside this lease");
        }
        previousOwnedOverride=old;
        ownedOverride=target;
        try {
            persistAndArm();
            if(!target.equals(old))shell.run("wm","size",target,"-d","0");
            if(!target.equals(readOverride()))throw new IOException("Android did not apply the requested logical size");
            return new int[]{logicalWidth,logicalHeight};
        } catch(Exception failure) {
            try { restoreBaseline(); clearLease(); }
            catch(Exception restoreFailure) { failure.addSuppressed(restoreFailure); }
            throw failure;
        }
    }

    synchronized void renew()throws Exception {
        if(!active)return;
        persistAndArm();
    }

    synchronized void release()throws Exception {
        if(!active)return;
        restoreBaseline();
        clearLease();
    }

    synchronized boolean active(){return active;}
    synchronized String identity(){return identity;}

    private void restoreBaseline()throws Exception {
        String current=readOverride();
        if(owned(current)){
            if(baselineOverride==null)shell.run("wm","size","reset","-d","0");
            else shell.run("wm","size",baselineOverride,"-d","0");
            String restored=readOverride();
            if(!same(baselineOverride,restored))throw new IOException("Android did not restore the previous logical size");
        }
        // If an unrelated size is now active, leave it alone and retire our stale guard.
    }

    private boolean owned(String current){
        return same(current,ownedOverride)||same(current,previousOwnedOverride);
    }

    private void persistAndArm()throws Exception {
        String baseMode=baselineOverride==null?"R":"O";
        int[] base=baselineOverride==null?new int[]{0,0}:dimensions(baselineOverride);
        String old=previousOwnedOverride==null?"none":previousOwnedOverride;
        String next=ownedOverride==null?"none":ownedOverride;
        String script="umask 077; mkdir -p /data/local/tmp && "
            +"printf '%s\\n' '"+baseMode+"' '"+base[0]+"' '"+base[1]+"' '"+old+"' '"+next+"' \"$(($(awk '{print int($1)}' /proc/uptime)+30))\" > "+STATE+".new && mv "+STATE+".new "+STATE+" || exit 1; "
            +"p=$(cat "+PID+" 2>/dev/null); alive=0; if [ -n \"$p\" ] && kill -0 \"$p\" 2>/dev/null && tr '\\000' ' ' < /proc/$p/cmdline 2>/dev/null | grep -Fq '"+STATE+"'; then alive=1; fi; "
            +"if [ \"$alive\" = 0 ]; then ("+WATCHER+") >/dev/null 2>&1 </dev/null & echo $! > "+PID+"; fi; test -f "+STATE;
        shell.run("sh","-c",script);
    }

    private void clearLease()throws Exception {
        shell.run("rm","-f",STATE,PID);
        active=false;identity=null;baselineOverride=null;ownedOverride=null;previousOwnedOverride=null;
    }

    private String readOverride()throws Exception {
        String output=shell.run("wm","size","-d","0");
        Matcher matcher=OVERRIDE.matcher(output==null?"":output);
        return matcher.find()?matcher.group(1):null;
    }

    private String run(String...args)throws Exception { return shell.run(args); }

    private void readSavedState(String output)throws IOException {
        // Watcher state is six newline-delimited, validated fields; ignore partial/corrupt data.
        String[] lines=output.trim().split("\\s+");
        if(lines.length!=6||!(lines[0].equals("R")||lines[0].equals("O")))throw new IOException("Unrecognized persisted display lease");
        if(lines[0].equals("R")){
            if(!lines[1].equals("0")||!lines[2].equals("0"))throw new IOException("Invalid native baseline state");
            baselineOverride=null;
        }else baselineOverride=parseDimension(lines[1])+"x"+parseDimension(lines[2]);
        previousOwnedOverride=lines[3].equals("none")?null:validSize(lines[3]);
        ownedOverride=lines[4].equals("none")?null:validSize(lines[4]);
        identity=null;
    }

    private static String validSize(String size)throws IOException {
        String[] values=size.split("x",-1);
        if(values.length!=2)throw new IOException("Invalid saved logical size");
        return parseDimension(values[0])+"x"+parseDimension(values[1]);
    }
    private static int[] dimensions(String size){String[] p=size.split("x");return new int[]{Integer.parseInt(p[0]),Integer.parseInt(p[1])};}
    private static int parseDimension(String value)throws IOException {
        try { int n=Integer.parseInt(value); if(n<320||n>8192)throw new NumberFormatException(); return n; }
        catch(NumberFormatException failure){throw new IOException("Invalid saved logical dimension",failure);}
    }
    private static boolean same(String left,String right){return left==null?right==null:left.equals(right);}
    private static void validate(int width,int height){
        if(width<320||height<320||width>8192||height>8192)throw new IllegalArgumentException("Logical dimensions must be between 320 and 8192");
    }
}
