package walksy.rawinput;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExternalMouseOwnerTest {
    @Test public void enteringAndLeavingEditorDiscardRawInputFromThePreviousOwner() throws Exception {
        RawInputHandler handler = new RawInputHandler();
        AtomicInteger x = (AtomicInteger) field("deltaX").get(handler);
        AtomicInteger y = (AtomicInteger) field("deltaY").get(handler);
        @SuppressWarnings("unchecked")
        ConcurrentLinkedQueue<Runnable> events =
            (ConcurrentLinkedQueue<Runnable>) field("mainThreadEventQueue").get(handler);
        AtomicInteger clicks = new AtomicInteger();
        x.set(80);
        y.set(-40);
        events.add(clicks::incrementAndGet);
        field("gameFocused").setBoolean(handler, true);
        ((AtomicInteger) field("focusTimer").get(handler)).set(5);

        handler.setExternalMouseOwner(true);

        assertTrue(handler.hasExternalMouseOwner());
        assertFalse(field("gameFocused").getBoolean(handler));
        assertEquals(0, ((AtomicInteger) field("focusTimer").get(handler)).get());
        assertEquals(0, handler.pollDeltaX(), 0);
        assertEquals(0, handler.pollDeltaY(), 0);
        handler.flushEvents(true);
        assertEquals(0, clicks.get());

        x.set(100);
        events.add(clicks::incrementAndGet);
        handler.setExternalMouseOwner(false);

        assertFalse(handler.hasExternalMouseOwner());
        assertEquals(0, handler.pollDeltaX(), 0);
        handler.flushEvents(true);
        assertEquals(0, clicks.get());
    }

    @Test public void repeatedEditorStateDoesNotKeepResettingInput() throws Exception {
        RawInputHandler handler = new RawInputHandler();
        handler.setExternalMouseOwner(true);
        AtomicInteger x = (AtomicInteger) field("deltaX").get(handler);
        x.set(12);
        handler.setExternalMouseOwner(true);
        assertEquals(12, handler.pollDeltaX(), 0);
    }

    private static Field field(String name) throws Exception {
        Field field = RawInputHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
