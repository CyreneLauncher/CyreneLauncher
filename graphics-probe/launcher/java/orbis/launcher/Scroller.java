package orbis.launcher;

/**
 * One scrollable area's offset in pixels. The wheel and the keys move a target; the shown offset
 * eases towards it each frame, so scrolling is smooth and never jumps a whole row. Scrolling only
 * moves the view: what is selected is the screen's business, and the view follows the selection
 * only when asked to ({@link #reveal}), which the screen does for controller and keyboard moves.
 */
final class Scroller {
    private float target, current, max;

    /** The wheel: amount is notches, positive up (GLFW); step is pixels per notch. */
    void wheel(float amount, float step) {
        target = clamp(target - amount * step);
    }

    /** Moves the target by pixels (keys scrolling a text). */
    void by(float pixels) {
        target = clamp(target + pixels);
    }

    /** Brings the span top..bottom (content pixels) into a view of the given height, moving as little as possible. */
    void reveal(float top, float bottom, float view) {
        if (top < target) target = top;
        else if (bottom > target + view) target = bottom - view;
        target = clamp(target);
    }

    /** Once per frame: the content and view heights, the frame time; returns the offset to draw at. */
    float update(float content, float view, float seconds) {
        max = Math.max(0, content - view);
        target = clamp(target);
        float step = Math.min(1f, Math.max(0f, seconds) * 14f);
        current += (target - current) * step;
        if (Math.abs(target - current) < 0.5f) current = target;
        current = Math.max(0, Math.min(max, current));
        return current;
    }

    void reset() {
        target = current = 0;
    }

    float target() { return target; }
    float offset() { return current; }
    float max() { return max; }
    boolean atTop() { return target <= 0.5f; }

    private float clamp(float value) {
        return Math.max(0, Math.min(max, value));
    }
}
