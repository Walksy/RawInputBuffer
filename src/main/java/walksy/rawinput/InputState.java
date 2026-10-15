package walksy.rawinput;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** Bounded button/wheel queue; movement has no per-packet allocation. */
final class InputState {
    static final int EVENT_BYTES = 22; // LWJGL 2: button, state, x, y, wheel, nanos
    private static final int MAX_EVENTS = 4096;
    private final ArrayDeque<InputEvent> events = new ArrayDeque<InputEvent>();
    private final boolean[] buttons = new boolean[5];
    private int dx;
    private int dy;
    private int wheelDelta;
    private boolean active;

    synchronized void activate(boolean[] held) {
        clear();
        System.arraycopy(held, 0, buttons, 0, buttons.length);
        active = true;
    }
    synchronized void deactivate() { active = false; clear(); }
    private void clear() {
        dx = dy = wheelDelta = 0;
        events.clear();
        java.util.Arrays.fill(buttons, false);
    }
    synchronized boolean isActive() { return active; }

    synchronized void packet(int x, int y, int flags, int wheel, boolean absolute) {
        if (!active) return;
        if (!absolute) { dx += x; dy -= y; }
        for (int button = 0; button < buttons.length; button++) {
            if ((flags & (1 << (button * 2))) != 0) add(button, true, 0);
            if ((flags & (2 << (button * 2))) != 0) add(button, false, 0);
        }
        if ((flags & 0x0400) != 0) {
            wheelDelta += wheel;
            add(-1, false, wheel);
        }
    }
    private void add(int button, boolean down, int wheel) {
        if (!active) return;
        if (events.size() == MAX_EVENTS) {
            // Fail open instead of dropping a release and leaving an attack held.
            deactivate();
            return;
        }
        if (button >= 0) buttons[button] = down;
        events.add(new InputEvent(button, down, wheel, System.nanoTime()));
    }
    synchronized int takeX() { int value = dx; dx = 0; return value; }
    synchronized int takeY() { int value = dy; dy = 0; return value; }
    synchronized int takeWheel() { int value = wheelDelta; wheelDelta = 0; return value; }
    synchronized boolean[] buttonSnapshot() { return buttons.clone(); }

    synchronized ByteBuffer appendEvents(ByteBuffer existing) {
        int needed = existing.remaining() + events.size() * EVENT_BYTES;
        if (needed > existing.capacity()) {
            ByteBuffer larger = ByteBuffer.allocate(needed).order(existing.order());
            larger.put(existing);
            existing = larger;
        } else {
            existing.compact();
        }
        InputEvent event;
        while ((event = events.poll()) != null) {
            existing.put((byte) event.button).put((byte) (event.down ? 1 : 0));
            existing.putInt(0).putInt(0).putInt(event.wheel).putLong(event.nanos);
        }
        existing.flip();
        return existing;
    }
    private static final class InputEvent {
        final int button;
        final boolean down;
        final int wheel;
        final long nanos;
        InputEvent(int button, boolean down, int wheel, long nanos) {
            this.button = button; this.down = down;
            this.wheel = wheel; this.nanos = nanos;
        }
    }
}
