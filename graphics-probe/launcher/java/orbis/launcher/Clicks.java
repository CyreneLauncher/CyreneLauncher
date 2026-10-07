package orbis.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the pointer can act, recorded while a frame is drawn and used for the next frame's input:
 * each region has what hovering does (usually moving the selection there) and what a left click
 * does. Later regions lie on top. A modal dialog calls {@link #modal} first, so nothing beneath it
 * answers. Scroll areas take the wheel over them.
 */
final class Clicks {
    interface Wheel { void scroll(float amount); }

    private record Region(float x, float y, float w, float h, Runnable hover, Runnable click) {
        boolean contains(float px, float py) { return px >= x && py >= y && px < x + w && py < y + h; }
    }
    private record Area(float x, float y, float w, float h, Wheel wheel) {
        boolean contains(float px, float py) { return px >= x && py >= y && px < x + w && py < y + h; }
    }

    private List<Region> building = new ArrayList<>(), live = new ArrayList<>();
    private List<Area> buildingAreas = new ArrayList<>(), liveAreas = new ArrayList<>();

    /** Starts a frame's regions; the previous frame's stay live until {@link #end}. */
    void begin() {
        building = new ArrayList<>();
        buildingAreas = new ArrayList<>();
    }

    void end() {
        live = building;
        liveAreas = buildingAreas;
    }

    /** hover or click may be null. */
    void add(float x, float y, float w, float h, Runnable hover, Runnable click) {
        if (w > 0 && h > 0) building.add(new Region(x, y, w, h, hover, click));
    }

    void wheel(float x, float y, float w, float h, Wheel wheel) {
        if (w > 0 && h > 0) buildingAreas.add(new Area(x, y, w, h, wheel));
    }

    /** Everything recorded so far this frame lies beneath a dialog and stops answering. */
    void modal() {
        building.clear();
        buildingAreas.clear();
    }

    /** Runs the hover of the topmost region under the point; whether there was one. */
    boolean hover(float x, float y) {
        Region r = at(x, y);
        if (r == null) return false;
        if (r.hover() != null) r.hover().run();
        return true;
    }

    /** Runs the topmost region's click; whether one answered. */
    boolean click(float x, float y) {
        Region r = at(x, y);
        if (r == null || r.click() == null) return false;
        if (r.hover() != null) r.hover().run();
        r.click().run();
        return true;
    }

    /** Scrolls the topmost scroll area under the point; whether one took it. */
    boolean scroll(float x, float y, float amount) {
        for (int i = liveAreas.size() - 1; i >= 0; i--)
            if (liveAreas.get(i).contains(x, y)) { liveAreas.get(i).wheel().scroll(amount); return true; }
        return false;
    }

    private Region at(float x, float y) {
        for (int i = live.size() - 1; i >= 0; i--) if (live.get(i).contains(x, y)) return live.get(i);
        return null;
    }
}
