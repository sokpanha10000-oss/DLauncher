package git.artdeell.mojo.input.buttons;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import git.artdeell.mojo.R;

/**
 * Joystick drawn with the Bedrock-style textures (res/drawable-nodpi/joystick_be_*.png).
 * Replaces the old third party JoystickView, and keeps the same small API surface
 * that {@link ControlJoystick} relies on.
 *
 * Angles follow the usual convention: 0 = east, 90 = north, counter-clockwise, 0..359.
 * Strength goes from 0 to 100 (percent of the maximum thumb travel).
 */
@SuppressLint("ViewConstructor")
public class BedrockJoystickView extends View {

    public interface OnMoveListener {
        void onMove(int angle, int strength);
        void onForwardLock(boolean isLocked);
    }

    /** Thumb size relative to the base size (the source art is 64px thumb on a 128px base) */
    private static final float THUMB_RATIO = 0.5f;

    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF mDst = new RectF();
    private final Bitmap mBaseBitmap;
    private final Bitmap mThumbBitmap;

    private OnMoveListener mListener;
    private int mDeadzonePercent = 0;
    private boolean mAbsolute = false;
    private int mForwardLockDistance = 0;

    private int mPointerId = -1;
    private float mOriginX, mOriginY;   // Where the movement is measured from
    private float mThumbDx, mThumbDy;   // Thumb offset from the view center, in px
    private boolean mForwardLocked = false;

    public BedrockJoystickView(Context context) {
        super(context);
        mBaseBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.joystick_be_base);
        mThumbBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.joystick_be_thumb);
    }

    public void setOnMoveListener(OnMoveListener listener) {
        mListener = listener;
    }

    /** Percentage (0-100) of the travel inside which no movement is reported */
    public void setDeadzone(int percent) {
        mDeadzonePercent = Math.max(0, Math.min(99, percent));
    }

    /**
     * true: the stick is measured from the view center, the thumb follows the finger directly.
     * false: the stick is measured from where the finger first touched the view.
     */
    public void setFixedCenter(boolean absolute) {
        mAbsolute = absolute;
    }

    /** Extra distance (px) past the maximum travel needed to trigger the forward lock, 0 disables it */
    public void setForwardLockDistance(int distancePx) {
        mForwardLockDistance = Math.max(0, distancePx);
    }

    private float maxTravel() {
        float size = Math.min(getWidth(), getHeight());
        return Math.max(1f, (size - size * THUMB_RATIO) / 2f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float size = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f;

        mDst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
        canvas.drawBitmap(mBaseBitmap, null, mDst, mPaint);

        float half = size * THUMB_RATIO / 2f;
        float tx = cx + mThumbDx, ty = cy + mThumbDy;
        mDst.set(tx - half, ty - half, tx + half, ty + half);
        canvas.drawBitmap(mThumbBitmap, null, mDst, mPaint);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (mPointerId != -1) return true; // Already tracking one finger
                int index = event.getActionIndex();
                mPointerId = event.getPointerId(index);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (mAbsolute) {
                    mOriginX = getWidth() / 2f;
                    mOriginY = getHeight() / 2f;
                } else {
                    mOriginX = event.getX(index);
                    mOriginY = event.getY(index);
                }
                // A fresh touch always clears a previous lock
                setForwardLocked(false);
                update(event.getX(index), event.getY(index));
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int index = event.findPointerIndex(mPointerId);
                if (index < 0) return true;
                update(event.getX(index), event.getY(index));
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                if (event.getPointerId(event.getActionIndex()) != mPointerId) return true;
                release();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                release();
                return true;
        }
        return true;
    }

    private void update(float x, float y) {
        float dx = x - mOriginX;
        float dy = y - mOriginY;
        float distance = (float) Math.hypot(dx, dy);
        float max = maxTravel();

        // Forward lock: finger dragged well beyond the top of the travel area
        boolean beyondTop = mForwardLockDistance > 0 && dy < 0
                && distance > max + mForwardLockDistance
                && Math.abs(dx) < Math.abs(dy);
        setForwardLocked(beyondTop);

        float clamped = Math.min(distance, max);
        float ux = distance == 0 ? 0 : dx / distance;
        float uy = distance == 0 ? 0 : dy / distance;
        if (mForwardLocked) { ux = 0; uy = -1; clamped = max; }

        mThumbDx = ux * clamped;
        mThumbDy = uy * clamped;
        invalidate();

        int strength = Math.round(clamped / max * 100f);
        if (strength < mDeadzonePercent) strength = 0;
        // Android's y axis points down, the angle convention points up
        double degrees = Math.toDegrees(Math.atan2(-uy, ux));
        if (degrees < 0) degrees += 360;
        if (mForwardLocked) { degrees = 90; strength = 100; }
        if (mListener != null) mListener.onMove((int) Math.round(degrees) % 360, strength);
    }

    private void setForwardLocked(boolean locked) {
        if (locked == mForwardLocked) return;
        mForwardLocked = locked;
        if (mListener != null) mListener.onForwardLock(locked);
    }

    private void release() {
        mPointerId = -1;
        setForwardLocked(false);
        mThumbDx = 0;
        mThumbDy = 0;
        invalidate();
        if (mListener != null) mListener.onMove(0, 0);
    }
}
