package git.artdeell.mojo.input.buttons;

import static git.artdeell.mojo.CallbackBridge.sendMouseButton;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import git.artdeell.mojo.game.GameActivity;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;

import git.artdeell.mojo.R;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.input.ControlData;
import git.artdeell.mojo.input.ControlLayout;
import git.artdeell.mojo.input.handleview.EditControlSideDialog;
import git.artdeell.mojo.prefs.LauncherPreferences;

import git.artdeell.mojo.CallbackBridge;

import java.io.File;

import static git.artdeell.mojo.game.platform.Platform.PLATFORM;

@SuppressLint({"ViewConstructor", "AppCompatCustomView"})
public class ControlButton extends TextView implements ControlInterface {
    private final Paint mRectPaint = new Paint();
    protected ControlData mProperties;
    private final ControlLayout mControlLayout;

    /* Cache value from the ControlData radius for drawing purposes */
    private float mComputedRadius;
    private boolean mHasBitmap;

    protected boolean mIsToggled = false;

    /**
     * Minecraft reads most inputs once per game tick (50ms). A tap that is released faster than
     * that is often never seen (for example a jump), so every press is held at least this long.
     */
    private static final long MIN_HOLD_MS = 100;
    /** Pace of the automatic double tap used by the fly action. Must stay above one tick. */
    private static final long FLY_STEP_MS = 90;
    // The platform layer takes Android key codes (not GLFW ones)
    private static final int KEY_SPACE = KeyEvent.KEYCODE_SPACE;
    private static final int KEY_T = KeyEvent.KEYCODE_T;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /**
     * Shapes every tap so that Minecraft sees it: each press is held for a minimum time, and there
     * is always a short moment with the key up between two presses (otherwise a fast second tap
     * looks like the key was never released and the game ignores it, for example a double jump).
     */
    private final TapShaper mShaper = new TapShaper(new TapShaper.Output() {
        @Override
        public void keyDown() {
            onKeyDownSent();
        }

        @Override
        public void keyUp() {
            sendKeyPresses(false);
        }
    }, new TapShaper.Timer() {
        @Override
        public long now() {
            return SystemClock.uptimeMillis();
        }

        @Override
        public void post(Runnable runnable, long delayMs) {
            mHandler.postDelayed(runnable, delayMs);
        }

        @Override
        public void cancel(Runnable runnable) {
            mHandler.removeCallbacks(runnable);
        }
    });

    /** Two presses closer than this are a double tap (Minecraft's flight toggle window is 350ms) */
    private static final long DOUBLE_TAP_MS = 300;
    /** When the jump key was really sent down the last time (not when the finger touched) */
    private long mLastKeyDownAt = 0;
    private final Runnable mFlightListener = () -> setBackground();
    private boolean mTracksFlight = false;

    public ControlButton(ControlLayout layout, ControlData properties) {
        super(layout.getContext());
        mControlLayout = layout;
        setGravity(Gravity.CENTER);
        setAllCaps(LauncherPreferences.PREF_BUTTON_ALL_CAPS);
        setTextColor(Color.WHITE);
        setPadding(4, 4, 4, 4);
        setTextSize(14); // Nullify the default size setting
        setOutlineProvider(null); // Disable shadow casting, removing one drawing pass

        //setOnLongClickListener(this);

        //When a button is created, the width/height has yet to be processed to fit the scaling.
        setProperties(preProcessProperties(properties, layout));

        injectBehaviors();
    }

    @Override
    public View getControlView() {return this;}

    public ControlData getProperties() {
        return mProperties;
    }

    private void setupBitmapTint() {
        // Idle state must stay untinted so the artwork is shown exactly as designed,
        // the highlight is only applied while the button is pressed or toggled on.
        int highlight;
        if (mProperties.isToggle) {
            final TypedValue value = new TypedValue();
            getContext().getTheme().resolveAttribute(R.attr.colorAccent, value, true);
            highlight = androidx.core.graphics.ColorUtils.setAlphaComponent(
                    value.data, BackgroundTint.BACKGROUND_TOGGLE_TINT_ALPHA);
        } else {
            highlight = androidx.core.graphics.ColorUtils.setAlphaComponent(
                    Color.WHITE, BackgroundTint.BACKGROUND_DEFAULT_TINT_ALPHA * 2);
        }
        ColorStateList tintStateList = new ColorStateList(
                new int[][]{ new int[]{android.R.attr.state_activated}, new int[]{} },
                new int[]{ highlight, Color.TRANSPARENT });
        setBackgroundTintList(tintStateList);
        setBackgroundTintMode(PorterDuff.Mode.SRC_ATOP);
    }

    private void setupNormalTint() {
        mComputedRadius = ControlInterface.super.computeCornerRadius(mProperties.cornerRadius);
        setBackgroundTintList(null);
        if (mProperties.isToggle) {
            //For the toggle layer
            final TypedValue value = new TypedValue();
            getContext().getTheme().resolveAttribute(R.attr.colorAccent, value, true);
            mRectPaint.setColor(value.data);
            mRectPaint.setAlpha(BackgroundTint.BACKGROUND_TOGGLE_TINT_ALPHA);
        } else {
            mRectPaint.setColor(Color.WHITE);
            mRectPaint.setAlpha(BackgroundTint.BACKGROUND_DEFAULT_TINT_ALPHA);
        }
    }

    public void setProperties(ControlData properties, boolean changePos) {
        mProperties = properties;
        ControlInterface.super.setProperties(properties, changePos);

        String shownTag = getShownBitmapTag();
        mHasBitmap = Tools.isValidString(shownTag);

        if(mHasBitmap) setupBitmapTint();
        else setupNormalTint();

        // The Keyboard and Mouse icons have no room for a label
        setText(properties.isIconOnly(shownTag) ? "" : properties.name);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Bitmap uses a tint list, so don't do any custom rendering
        if(mHasBitmap || !isActivated()) return;
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), mComputedRadius, mComputedRadius, mRectPaint);
    }

    @Override
    public boolean isActivated() {
        // Any possible side effects?
        return super.isActivated() || (mProperties.isToggle && mIsToggled);
    }

    public void loadEditValues(EditControlSideDialog editControlPopup){
        editControlPopup.loadValues(getProperties());
    }

    /** Add another instance of the ControlButton to the parent layout */
    public void cloneButton(){
        ControlData cloneData = new ControlData(getProperties());
        cloneData.dynamicX = "0.5 * ${screen_width}";
        cloneData.dynamicY = "0.5 * ${screen_height}";
        ((ControlLayout) getParent()).addControlButton(cloneData);
    }

    /** Remove any trace of this button from the layout */
    public void removeButton() {
        ControlLayout parent = getControlLayoutParent();
        if(parent == null) return;
        parent.getLayout().mControlDataList.remove(getProperties());
        parent.removeView(this);
    }

    @Override
    public void handlePressed() {
        if(!getProperties().isToggle) mShaper.press();
    }

    @Override
    public void handleReleased() {
        if(triggerToggle()) return;
        mShaper.release();
    }

    /** The key really went down in the game */
    private void onKeyDownSent() {
        sendKeyPresses(true);
        detectDoubleJump(SystemClock.uptimeMillis());
    }

    /** The image shown while flying, for the jump button (double tap) or a follower (sneak) */
    private String getFlyBitmapTag() {
        ControlData properties = getProperties();
        if(Tools.isValidString(properties.doubleTapBitmapTag)) return properties.doubleTapBitmapTag;
        if(Tools.isValidString(properties.flyBitmapTag)) return properties.flyBitmapTag;
        return null;
    }

    @Override
    public String getActiveBitmapTag() {
        String flyTag = getFlyBitmapTag();
        if(flyTag != null && FlightTracker.isFlying()) return flyTag;
        return getProperties().bitmapTag;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Only in the game: the editor has no game log and nothing to follow
        if(getFlyBitmapTag() == null || !(getContext() instanceof GameActivity)) return;
        mTracksFlight = true;
        FlightTracker.addListener(mFlightListener);
        FlightTracker.start(findSavesDirectory());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mShaper.cancel(); // never leave a key stuck down
        if(!mTracksFlight) return;
        mTracksFlight = false;
        FlightTracker.removeListener(mFlightListener);
        FlightTracker.stop();
    }

    /** The "saves" folder of the instance that is running, where the worlds are stored */
    private File findSavesDirectory() {
        try {
            Instance instance = Instances.loadSelectedInstance();
            if(instance != null) return new File(instance.getGameDirectory(), "saves");
        }catch (Exception e) {
            Log.w("ControlButton", "Could not find the saves folder", e);
        }
        return null;
    }

    /**
     * The jump button: a double jump is Minecraft's flight toggle. It is judged by the moments the
     * key was really sent to the game, so a second tap that the game could not have seen as a new
     * press never counts. In survival or adventure the toggle cannot start flight, so nothing
     * changes there. Like MCPE the image only changes when flight starts or stops.
     */
    private void detectDoubleJump(long sentAt) {
        if(!Tools.isValidString(getProperties().doubleTapBitmapTag)) return;
        if(mLastKeyDownAt != 0 && sentAt - mLastKeyDownAt <= DOUBLE_TAP_MS) {
            mLastKeyDownAt = 0;
            boolean flying = FlightTracker.isFlying();
            if(!flying && !FlightTracker.canFly()) return;
            FlightTracker.setFlyingByUser(!flying);
        }else {
            mLastKeyDownAt = sentAt;
        }
    }

    private void sendRawKey(int keycode, boolean isDown) {
        CallbackBridge.setModifiers(keycode, isDown);
        PLATFORM.sendKeyEvent(keycode, isDown ? 1 : 0, CallbackBridge.getCurrentMods());
    }

    /** Space, Space again shortly after: toggles flight in creative mode */
    private void toggleFlight() {
        sendRawKey(KEY_SPACE, true);
        mHandler.postDelayed(() -> sendRawKey(KEY_SPACE, false), FLY_STEP_MS);
        mHandler.postDelayed(() -> sendRawKey(KEY_SPACE, true), FLY_STEP_MS * 2);
        mHandler.postDelayed(() -> sendRawKey(KEY_SPACE, false), FLY_STEP_MS * 3);
    }

    /** Opens the chat and shows the on-screen keyboard so you can type right away */
    private void openChat() {
        sendRawKey(KEY_T, true);
        mHandler.postDelayed(() -> sendRawKey(KEY_T, false), MIN_HOLD_MS);
        mHandler.postDelayed(() -> GameActivity.toggleKeyboardState(true, 0), MIN_HOLD_MS + 100);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        ControlData properties = getProperties();
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP: // 1
            case MotionEvent.ACTION_CANCEL: // 3
            case MotionEvent.ACTION_POINTER_UP: // 6
                if(properties.passThruEnabled){
                    //Send the event to be taken as a mouse action
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if(gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }
                break;
        }

        if(getProperties().isSwipeable) {
            getControlLayoutParent().onTouch(this, event);
            return true;
        }

        switch (action){
            case MotionEvent.ACTION_DOWN: // 0
            case MotionEvent.ACTION_POINTER_DOWN: // 5
                handlePressed();
                break;
            case MotionEvent.ACTION_UP: // 1
            case MotionEvent.ACTION_CANCEL: // 3
            case MotionEvent.ACTION_POINTER_UP: // 6
                handleReleased();
                break;
            default:
                return false;
        }

        return super.onTouchEvent(event);
    }



    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean triggerToggle(){
        //returns true a the toggle system is triggered
        if(mProperties.isToggle){
            mIsToggled = !mIsToggled;
            invalidate();
            sendKeyPresses(mIsToggled);
            return true;
        }
        return false;
    }

    public void sendKeyPresses(boolean isDown){
        setActivated(isDown);
        for(int keycode : mProperties.keycodes){
            if(keycode >= KeyEvent.KEYCODE_UNKNOWN){
                CallbackBridge.setModifiers(keycode, isDown);
                int modifiers = CallbackBridge.getCurrentMods();
                PLATFORM.sendKeyEvent(keycode, isDown ? 1 : 0, modifiers);
            }else{
                Log.i("punjabilauncher", "sendSpecialKey("+keycode+","+isDown+")");
                sendSpecialKey(keycode, isDown);
            }
        }
    }

    private void sendSpecialKey(int keycode, boolean isDown){
        switch (keycode) {
            case ControlData.SPECIALBTN_KEYBOARD:
                if(isDown) GameActivity.switchKeyboardState(false);
                break;

            case ControlData.SPECIALBTN_KEYBOARDPAN:
                if(isDown) GameActivity.switchKeyboardState(true);
                break;

            case ControlData.SPECIALBTN_TOGGLECTRL:
                if(isDown)getControlLayoutParent().toggleControlVisible();
                break;

            case ControlData.SPECIALBTN_VIRTUALMOUSE:
                if(isDown) GameActivity.toggleMouse(getContext());
                break;

            case ControlData.SPECIALBTN_MOUSEPRI:
                sendMouseButton(MotionEvent.BUTTON_PRIMARY, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSEMID:
                sendMouseButton(MotionEvent.BUTTON_TERTIARY, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSESEC:
                sendMouseButton(MotionEvent.BUTTON_SECONDARY, isDown);
                break;

            case ControlData.SPECIALBTN_SCROLLDOWN:
                if (!isDown) CallbackBridge.sendScroll(0, 1d);
                break;

            case ControlData.SPECIALBTN_SCROLLUP:
                if (!isDown) CallbackBridge.sendScroll(0, -1d);
                break;
            case ControlData.SPECIALBTN_MENU:
                mControlLayout.notifyAppMenu();
                break;

            case ControlData.SPECIALBTN_FLY:
                if(isDown) toggleFlight();
                break;

            case ControlData.SPECIALBTN_CHAT:
                if(isDown) openChat();
                break;
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
