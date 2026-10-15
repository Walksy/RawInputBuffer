package walksy.rawinput;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.input.Mouse;
import static org.junit.Assert.*;

/** Validate the packet format against Minecraft 1.8.9's real LWJGL Mouse decoder. */
public class LwjglEventCompatibilityTest {
    @Test public void lwjglReadsInjectedButtonsWheelAndMovement() throws Exception {
        Assume.assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"));
        Map<Field, Object> saved = new HashMap<Field, Object>();
        try {
            set(saved, "created", true);
            set(saved, "isGrabbed", true);
            set(saved, "buttons", ByteBuffer.allocate(5));
            set(saved, "buttonCount", 5);
            set(saved, "dx", 35);
            set(saved, "dy", -17);
            assertEquals(35, Mouse.getDX());
            assertEquals(-17, Mouse.getDY());
            assertEquals(0, Mouse.getDX());
            InputState state = new InputState();
            state.activate(new boolean[5]);
            state.packet(0, 0, 1, 0, false);
            state.packet(0, 0, 2, 0, false);
            state.packet(0, 0, 0x400, -120, false);
            ByteBuffer empty = ByteBuffer.allocate(22);
            empty.limit(0);
            set(saved, "readBuffer", state.appendEvents(empty));
            assertTrue(Mouse.next());
            assertEquals(0, Mouse.getEventButton());
            assertTrue(Mouse.getEventButtonState());
            assertEquals(0, Mouse.getEventDX());
            assertEquals(0, Mouse.getEventDY());
            assertTrue(Mouse.next());
            assertFalse(Mouse.getEventButtonState());
            assertTrue(Mouse.next());
            assertEquals(-1, Mouse.getEventButton());
            assertEquals(-120, Mouse.getEventDWheel());
            assertFalse(Mouse.next());
        } finally {
            for (Map.Entry<Field, Object> entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
        }
    }

    private static void set(Map<Field, Object> saved, String name, Object value) throws Exception {
        Field field = Mouse.class.getDeclaredField(name);
        field.setAccessible(true);
        saved.put(field, field.get(null));
        field.set(null, value);
    }
}
