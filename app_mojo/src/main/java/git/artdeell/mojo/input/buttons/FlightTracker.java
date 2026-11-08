package git.artdeell.mojo.input.buttons;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import git.artdeell.mojo.Tools;

/**
 * Best-effort knowledge about creative flight, shared by the jump and sneak buttons.
 *
 * The launcher cannot read the player's real flight state from the game, so:
 *  - "flying" is assumed when the jump button is double tapped (Minecraft toggles flight on a
 *    double jump) and cleared by another double tap;
 *  - the game mode is read from the game log (messages like "Set own game mode to Creative Mode")
 *    so that a double jump in survival or adventure, where it cannot start flight, is ignored.
 */
public final class FlightTracker {
    private FlightTracker() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /** null = no game mode message seen yet, so flight is assumed to be possible */
    private static volatile Boolean sCanFly = null;
    private static volatile boolean sFlying = false;
    private static volatile boolean sRunning = false;

    public static boolean isFlying() {
        return sFlying;
    }

    /** False only when the log showed that the player is in survival or adventure mode */
    public static boolean canFly() {
        return !Boolean.FALSE.equals(sCanFly);
    }

    /** Call from the main thread */
    public static void setFlying(boolean flying) {
        if(sFlying == flying) return;
        sFlying = flying;
        notifyListeners();
    }

    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        for(Runnable listener : LISTENERS) listener.run();
    }

    private static int sUsers = 0;
    private static volatile Thread sThread;

    /** A button that needs flight info calls this when it appears. Every start needs a stop. */
    public static synchronized void start() {
        sUsers++;
        if(sThread != null) return;
        sRunning = true;
        sCanFly = null;
        sFlying = false;
        sThread = new Thread(FlightTracker::followLog, "FlightTracker");
        sThread.setDaemon(true);
        sThread.start();
    }

    public static synchronized void stop() {
        if(sUsers > 0) sUsers--;
        if(sUsers > 0) return;
        sRunning = false;
        sThread = null;
    }

    private static void followLog() {
        File log = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        long position = 0;
        byte[] chunk = new byte[8192];
        StringBuilder pending = new StringBuilder();
        final Thread self = Thread.currentThread();
        while(sRunning && self == sThread) {
            try {
                if(log.isFile()) {
                    long length = log.length();
                    if(length < position) { // The log was reset by a new game start
                        position = 0;
                        pending.setLength(0);
                        sCanFly = null;
                        MAIN.post(() -> setFlying(false));
                    }
                    if(length > position) {
                        try(RandomAccessFile file = new RandomAccessFile(log, "r")) {
                            file.seek(position);
                            int read;
                            while((read = file.read(chunk)) > 0) {
                                position += read;
                                pending.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                            }
                        }
                        int newline;
                        while((newline = pending.indexOf("\n")) >= 0) {
                            handleLine(pending.substring(0, newline));
                            pending.delete(0, newline + 1);
                        }
                    }
                }
                Thread.sleep(500);
            }catch(InterruptedException e) {
                return;
            }catch(Exception e) {
                // The log is only a hint, never let it break the game
                try { Thread.sleep(2000); } catch(InterruptedException ie) { return; }
            }
        }
    }

    private static void handleLine(String line) {
        String text = line.toLowerCase(Locale.ROOT);
        if(!text.contains("game mode") || text.contains("default")) return;
        if(text.contains("creative") || text.contains("spectator")) {
            sCanFly = Boolean.TRUE;
        }else if(text.contains("survival") || text.contains("adventure")) {
            sCanFly = Boolean.FALSE;
            MAIN.post(() -> setFlying(false)); // flight is gone with the game mode
        }
    }
}
