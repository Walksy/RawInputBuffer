package walksy.rawinput;

import com.sun.jna.platform.win32.User32;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.logging.log4j.util.TriConsumer;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.glfw.GLFW;
import static org.junit.Assert.*;

public class InventoryInputTest {
    @Test public void clickAndScrollUseTheirCapturedCursorAndModifiersInOrder() throws Exception {
        TestHandler handler = new TestHandler();
        List<String> events = callbacks(handler);
        handler.position = new RawInputHandler.CursorPosition(100, 200);
        handler.modifiers = GLFW.GLFW_MOD_SHIFT;
        buttons(handler, 1);
        handler.position = new RawInputHandler.CursorPosition(150, 250);
        scroll(handler, (short) 120, 0x400);
        handler.position = new RawInputHandler.CursorPosition(175, 275);
        handler.modifiers = GLFW.GLFW_MOD_CONTROL;
        buttons(handler, 2);
        handler.position = new RawInputHandler.CursorPosition(300, 400);
        handler.modifiers = 0;
        assertTrue(events.isEmpty());

        handler.flushEvents(true);
        handler.updateCursorPosition();

        assertEquals(List.of("move:100.0:200.0", "button:0:1:1", "move:150.0:250.0",
            "scroll:0.0:1.0", "move:175.0:275.0", "button:0:2:0", "move:300.0:400.0"), events);
    }

    @Test public void latestCursorIsSampledOnceAfterManyMovementPackets() throws Exception {
        TestHandler handler = new TestHandler();
        List<String> events = callbacks(handler);
        for (int i = 0; i < 8000; i++) handler.position = new RawInputHandler.CursorPosition(i, i);
        handler.updateCursorPosition();
        assertEquals(List.of("move:7999.0:7999.0"), events);
        assertEquals(1, handler.samples);
    }

    @Test public void discardedEventsDoNotMoveCursorOrClickAfterFocusLoss() throws Exception {
        TestHandler handler = new TestHandler();
        List<String> events = callbacks(handler);
        buttons(handler, 1);
        scroll(handler, (short) -120, 0x400);
        handler.flushEvents(false);
        handler.flushEvents(true);
        assertTrue(events.isEmpty());
    }

    @Test public void horizontalWheelUsesGlfwSignAndKeepsItsCursorPosition() throws Exception {
        TestHandler handler = new TestHandler();
        List<String> events = callbacks(handler);
        scroll(handler, (short) 120, 0x800);
        handler.flushEvents(true);
        assertEquals(List.of("move:0.0:0.0", "scroll:-1.0:0.0"), events);
    }

    @Test public void nativeCursorCanBeConvertedToWindowClientCoordinates() throws Exception {
        Assume.assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"));
        RawInputHandler handler = new RawInputHandler();
        set(handler, "gameWindowHwnd", User32.INSTANCE.GetDesktopWindow());
        assertNotNull(handler.readCursorPosition());
    }

    @Test public void focusLossReleasesDeliveredButtonsEvenIfTheirQueuedReleaseWasDiscarded() throws Exception {
        TestHandler handler = new TestHandler();
        List<String> events = callbacks(handler);
        buttons(handler, 1);
        handler.flushEvents(true);
        buttons(handler, 2);
        handler.flushEvents(false);
        handler.releaseButtons();
        handler.releaseButtons();
        assertEquals(List.of("move:0.0:0.0", "button:0:0:1", "button:0:0:0"), events);
    }

    private static List<String> callbacks(RawInputHandler handler) throws Exception {
        List<String> events = new ArrayList<>();
        TriConsumer<Long, Double, Double> cursor = (window, x, y) -> events.add("move:" + x + ":" + y);
        TriConsumer<Long, MouseInput, Integer> button = (window, input, action) ->
            events.add("button:" + input.button() + ":" + input.modifiers() + ":" + action);
        TriConsumer<Long, Double, Double> wheel = (window, x, y) -> events.add("scroll:" + x + ":" + y);
        set(handler, "cursorCallback", cursor);
        set(handler, "buttonCallback", button);
        set(handler, "scrollCallback", wheel);
        return events;
    }

    private static void buttons(RawInputHandler handler, int flags) throws Exception {
        Method method = RawInputHandler.class.getDeclaredMethod("handleButtons", int.class);
        method.setAccessible(true);
        method.invoke(handler, flags);
    }

    private static void scroll(RawInputHandler handler, short data, int flags) throws Exception {
        Method method = RawInputHandler.class.getDeclaredMethod("handleScroll", short.class, int.class);
        method.setAccessible(true);
        method.invoke(handler, data, flags);
    }

    private static void set(RawInputHandler handler, String name, Object value) throws Exception {
        Field field = RawInputHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(handler, value);
    }

    private static class TestHandler extends RawInputHandler {
        CursorPosition position = new CursorPosition(0, 0);
        int modifiers;
        int samples;
        @Override protected CursorPosition readCursorPosition() { samples++; return position; }
        @Override protected int readModifiers() { return modifiers; }
    }
}
