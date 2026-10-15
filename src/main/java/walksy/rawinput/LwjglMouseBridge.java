package walksy.rawinput;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

/** All reflection is limited to the unobfuscated LWJGL 2 API implementation. */
final class LwjglMouseBridge {
    private final Field dx = field(Mouse.class, "dx");
    private final Field dy = field(Mouse.class, "dy");
    private final Field wheel = field(Mouse.class, "dwheel");
    private final Field events = field(Mouse.class, "readBuffer");
    private final Field buttons = field(Mouse.class, "buttons");
    private final Object windowsDisplay;
    private final Field nativeMouse;
    private final Field hwnd;
    private final Field nativeButtons;
    private final Field nativeDx;
    private final Field nativeDy;
    private final Field nativeWheel;
    private Object windowsMouse;
    private boolean captured;

    LwjglMouseBridge() throws ReflectiveOperationException {
        Method implementation = Display.class.getDeclaredMethod("getImplementation");
        implementation.setAccessible(true);
        windowsDisplay = implementation.invoke(null);
        if (!windowsDisplay.getClass().getName().equals("org.lwjgl.opengl.WindowsDisplay")) {
            throw new IllegalStateException("This port requires standard Windows LWJGL 2");
        }
        nativeMouse = field(windowsDisplay.getClass(), "mouse");
        hwnd = field(windowsDisplay.getClass(), "hwnd");
        refreshMouse();
        nativeButtons = field(windowsMouse.getClass(), "button_states");
        nativeDx = field(windowsMouse.getClass(), "accum_dx");
        nativeDy = field(windowsMouse.getClass(), "accum_dy");
        nativeWheel = field(windowsMouse.getClass(), "accum_dwheel");
    }

    long windowHandle() throws IllegalAccessException {
        return hwnd.getLong(windowsDisplay);
    }

    void update(RawInputHandler handler, boolean frame) throws ReflectiveOperationException {
        refreshMouse(); // Fullscreen changes can recreate LWJGL's native mouse.
        if (handler.updateWindow(windowHandle()) && captured) {
            reset(handler.physicalButtons());
            captured = false;
        }
        boolean active = handler.setCaptured(Mouse.isCreated() && Mouse.isGrabbed() && Display.isActive());
        if (active != captured) {
            reset(handler.physicalButtons());
            captured = active;
        }
        if (!active) return;
        synchronized (handler.state) {
            syncButtons(handler.state.buttonSnapshot());
            events.set(null, handler.state.appendEvents((ByteBuffer) events.get(null)));
            wheel.setInt(null, wheel.getInt(null) + handler.state.takeWheel());
            if (frame) {
                dx.setInt(null, handler.state.takeX());
                dy.setInt(null, handler.state.takeY());
            }
        }
    }

    void release(RawInputHandler handler) throws ReflectiveOperationException {
        handler.setCaptured(false);
        if (captured) reset(handler.physicalButtons());
        captured = false;
    }

    private void refreshMouse() throws IllegalAccessException {
        windowsMouse = nativeMouse.get(windowsDisplay);
        if (windowsMouse == null) throw new IllegalStateException("LWJGL mouse is unavailable");
    }

    private void reset(boolean[] held) throws IllegalAccessException {
        dx.setInt(null, 0);
        dy.setInt(null, 0);
        wheel.setInt(null, 0);
        ByteBuffer buffer = (ByteBuffer) events.get(null);
        buffer.clear();
        buffer.limit(0);
        syncButtons(held);
        nativeDx.setInt(windowsMouse, 0);
        nativeDy.setInt(windowsMouse, 0);
        nativeWheel.setInt(windowsMouse, 0);
    }

    private void syncButtons(boolean[] held) throws IllegalAccessException {
        ByteBuffer publicState = (ByteBuffer) buttons.get(null);
        byte[] nativeState = (byte[]) nativeButtons.get(windowsMouse);
        for (int i = 0; i < held.length && i < nativeState.length && i < publicState.capacity(); i++) {
            byte value = (byte) (held[i] ? 1 : 0);
            publicState.put(i, value);
            nativeState[i] = value;
        }
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Unsupported LWJGL field: " + name, error);
        }
    }
}
