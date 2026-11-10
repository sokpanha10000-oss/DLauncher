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
 * Knowledge about creative flight, shared by the jump and sneak buttons.
 *
 * The game does not tell the launcher whether the player is flying, so three sources are combined:
 *  - the world save: when a world is opened, its player data says whether the player can fly
 *    (creative / spectator) and whether he was flying, and every autosave refreshes this;
 *  - the game log: game mode messages such as "Set own game mode to Survival Mode";
 *  - the jump button: a double jump toggles flight in Minecraft, but only if flight is possible.
 * When none of these knows the game mode (for example on a server) flight is assumed possible.
 */
public final class FlightTracker {
    private FlightTracker() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private static final long LOG_POLL_MS = 500;
    private static final int WORLD_POLL_EVERY = 4; // every 2 seconds
    /** Within this time after a double jump the save files are not trusted over the player */
    private static final long USER_TOGGLE_GRACE_MS = 10_000;

    /** null = unknown, so flight is assumed to be possible */
    private static volatile Boolean sCanFly = null;
    private static volatile boolean sFlying = false;
    private static volatile boolean sRunning = false;
    private static volatile long sLastUserToggle = 0;

    private static int sUsers = 0;
    private static volatile Thread sThread;
    private static volatile File sSavesDir;
    private static volatile long sStartedAt;
    private static long sLastWorldOpened;
    private static long sLastWorldSaved;

    public static boolean isFlying() {
        return sFlying;
    }

    /** False only when the game mode is known to be survival or adventure */
    public static boolean canFly() {
        return !Boolean.FALSE.equals(sCanFly);
    }

    /** The player double jumped. Call from the main thread */
    public static void setFlyingByUser(boolean flying) {
        sLastUserToggle = System.currentTimeMillis();
        setFlying(flying);
    }

    /** Call from the main thread */
    public static void setFlying(boolean flying) {
        if(sFlying == flying) return;
        sFlying = flying;
        for(Runnable listener : LISTENERS) listener.run();
    }

    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /**
     * A button that needs flight info calls this when it appears. Every start needs a stop.
     * @param savesDir the "saves" folder of the running instance, may be null
     */
    public static synchronized void start(File savesDir) {
        sUsers++;
        if(sThread != null) return;
        sRunning = true;
        sCanFly = null;
        sFlying = false;
        sSavesDir = savesDir;
        sStartedAt = System.currentTimeMillis() - 1000;
        sLastWorldOpened = 0;
        sLastWorldSaved = 0;
        sThread = new Thread(FlightTracker::follow, "FlightTracker");
        sThread.setDaemon(true);
        sThread.start();
    }

    public static synchronized void stop() {
        if(sUsers > 0) sUsers--;
        if(sUsers > 0) return;
        sRunning = false;
        sThread = null;
    }

    private static void follow() {
        File log = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        long position = 0;
        byte[] chunk = new byte[8192];
        StringBuilder pending = new StringBuilder();
        int tick = 0;
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
                            handleLogLine(pending.substring(0, newline));
                            pending.delete(0, newline + 1);
                        }
                    }
                }
                if(++tick % WORLD_POLL_EVERY == 0) pollWorld();
                Thread.sleep(LOG_POLL_MS);
            }catch(InterruptedException e) {
                return;
            }catch(Exception e) {
                // These are only hints, never let them break the game
                try { Thread.sleep(2000); } catch(InterruptedException ie) { return; }
            }
        }
    }

    /** Game mode messages from the log, for example "Set own game mode to Creative Mode" */
    static void handleLogLine(String line) {
        String text = line.toLowerCase(Locale.ROOT);
        if(!text.contains("game mode") || text.contains("default")) return;
        if(text.contains("creative") || text.contains("spectator")) {
            sCanFly = Boolean.TRUE;
        }else if(text.contains("survival") || text.contains("adventure")) {
            sCanFly = Boolean.FALSE;
            MAIN.post(() -> setFlying(false)); // flight is gone with the game mode
        }
    }

    /** The abilities saved in the world that was opened since the game started */
    static void pollWorld() {
        File saves = sSavesDir;
        if(saves == null) return;
        WorldAbilities.Snapshot snapshot = WorldAbilities.readLatest(saves);
        // A world older than this game session says nothing about the current one
        if(snapshot == null || snapshot.openedAt < sStartedAt) return;

        if(snapshot.openedAt != sLastWorldOpened) {
            // A world was just opened: its player data is exactly the state the game starts with
            sLastWorldOpened = snapshot.openedAt;
            sLastWorldSaved = snapshot.savedAt;
            sCanFly = snapshot.mayFly;
            final boolean flying = snapshot.mayFly && snapshot.flying;
            MAIN.post(() -> setFlying(flying));
        }else if(snapshot.savedAt != sLastWorldSaved) {
            // An autosave: the game mode may have changed, and flight may have ended by landing
            sLastWorldSaved = snapshot.savedAt;
            sCanFly = snapshot.mayFly;
            final boolean flying = snapshot.mayFly && snapshot.flying;
            if(System.currentTimeMillis() - sLastUserToggle > USER_TOGGLE_GRACE_MS) {
                MAIN.post(() -> setFlying(flying));
            }
        }
    }
}
