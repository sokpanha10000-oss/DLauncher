package git.artdeell.mojo.input.buttons;

/**
 * Turns finger presses into key events that Minecraft really notices.
 *
 * Minecraft reads most keys once per tick (50ms). Two things make a tap invisible to it:
 * a press that is released within the same tick, and two presses with no tick in between
 * (the key never looks released). So this class guarantees that
 *  - every key press is held for at least MIN_HOLD_MS, and
 *  - there are at least MIN_GAP_MS of "key up" between two presses.
 * A press that is held longer than MIN_HOLD_MS simply stays down until the finger is lifted.
 *
 * The class has no Android dependencies, time and scheduling come from the {@link Timer}.
 */
final class TapShaper {
    interface Output {
        void keyDown();
        void keyUp();
    }

    interface Timer {
        long now();
        void post(Runnable runnable, long delayMs);
        void cancel(Runnable runnable);
    }

    static final long MIN_HOLD_MS = 100;
    static final long MIN_GAP_MS = 70;

    private final Output mOutput;
    private final Timer mTimer;

    private boolean mFingerDown;
    private boolean mKeyDown;
    private long mDownSentAt;
    private long mUpSentAt = Long.MIN_VALUE / 4; // "a long time ago"
    private boolean mDownPending;
    private boolean mUpPending;
    private long mUpDueAt;

    private final Runnable mDownTask = this::sendDown;
    private final Runnable mUpTask = this::sendUp;

    TapShaper(Output output, Timer timer) {
        mOutput = output;
        mTimer = timer;
    }

    /** The finger touched the button */
    void press() {
        if(mFingerDown) return;
        mFingerDown = true;
        if(mDownPending) return;
        long now = mTimer.now();
        long downAt;
        if(mKeyDown) {
            // The previous tap is still being held for its minimum time: finish it first
            long upAt = mUpPending ? mUpDueAt : Math.max(now, mDownSentAt + MIN_HOLD_MS);
            scheduleUp(upAt - now);
            downAt = upAt + MIN_GAP_MS;
        }else {
            downAt = Math.max(now, mUpSentAt + MIN_GAP_MS);
        }
        scheduleDown(downAt - now);
    }

    /** The finger left the button */
    void release() {
        if(!mFingerDown) return;
        mFingerDown = false;
        if(mDownPending) return; // sendDown() schedules the matching key up
        if(mKeyDown) {
            long now = mTimer.now();
            scheduleUp(Math.max(now, mDownSentAt + MIN_HOLD_MS) - now);
        }
    }

    /** Drop everything that is waiting and never leave a key stuck down */
    void cancel() {
        mTimer.cancel(mDownTask);
        mTimer.cancel(mUpTask);
        mDownPending = false;
        mUpPending = false;
        mFingerDown = false;
        if(mKeyDown) {
            mKeyDown = false;
            mUpSentAt = mTimer.now();
            mOutput.keyUp();
        }
    }

    private void scheduleDown(long delay) {
        mDownPending = true;
        if(delay <= 0) {
            sendDown();
        }else {
            mTimer.post(mDownTask, delay);
        }
    }

    private void scheduleUp(long delay) {
        if(mUpPending) return;
        if(delay <= 0) {
            sendUp();
            return;
        }
        mUpPending = true;
        mUpDueAt = mTimer.now() + delay;
        mTimer.post(mUpTask, delay);
    }

    private void sendDown() {
        mDownPending = false;
        if(mKeyDown) return;
        mKeyDown = true;
        mDownSentAt = mTimer.now();
        mOutput.keyDown();
        if(!mFingerDown) scheduleUp(MIN_HOLD_MS);
    }

    private void sendUp() {
        mUpPending = false;
        if(!mKeyDown) return;
        mKeyDown = false;
        mUpSentAt = mTimer.now();
        mOutput.keyUp();
    }
}
