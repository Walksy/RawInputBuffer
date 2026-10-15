package walksy.rawinput;

import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class InputStateTest {
    private InputState active() {
        InputState state = new InputState();
        state.activate(new boolean[5]);
        return state;
    }

    @Test public void accumulatesEightKhzMovementWithoutQueuingMovementEvents() {
        InputState state = active();
        for (int i = 0; i < 8000; i++) state.packet(3, -2, 0, 0, false);
        assertEquals(24000, state.takeX());
        assertEquals(16000, state.takeY());
        assertEquals(0, state.takeX());
        assertEquals(0, state.takeY());
        assertEquals(0, state.appendEvents(empty(22)).remaining());
    }

    @Test public void absoluteDevicesDoNotBecomeRelativeCameraMovement() {
        InputState state = active();
        state.packet(65535, 65535, 1, 0, true);
        assertEquals(0, state.takeX());
        assertEquals(0, state.takeY());
        assertTrue(state.buttonSnapshot()[0]);
    }

    @Test public void serializesAllFiveButtonsAndSignedWheelInLwjglOrder() {
        InputState state = active();
        state.packet(0, 0, 0x155, 0, false);
        state.packet(0, 0, 0x2aa, 0, false);
        state.packet(0, 0, 0x400, -120, false);
        ByteBuffer events = state.appendEvents(empty(22));
        assertEquals(11 * 22, events.remaining());
        for (int i = 0; i < 10; i++) {
            assertEvent(events, i % 5, i < 5, 0);
        }
        assertEvent(events, -1, false, -120);
        assertEquals(-120, state.takeWheel());
        assertEquals(0, state.takeWheel());
        assertFalse(events.hasRemaining());
        for (boolean button : state.buttonSnapshot()) assertFalse(button);
    }

    @Test public void preservesUnreadLegacyEventsWhenGrowingOrCompacting() {
        for (int capacity : new int[]{22, 100}) {
            InputState state = active();
            ByteBuffer existing = ByteBuffer.allocate(capacity);
            existing.putInt(12345).putInt(54321).flip();
            existing.getInt();
            state.packet(0, 0, 1, 0, false);
            ByteBuffer result = state.appendEvents(existing);
            assertEquals(54321, result.getInt());
            assertEvent(result, 0, true, 0);
            assertFalse(result.hasRemaining());
        }
    }

    @Test public void focusTransitionsDiscardQueuedInputAndHeldState() {
        InputState state = active();
        state.packet(50, 90, 1 | 0x400, 120, false);
        state.deactivate();
        state.packet(10, 10, 1, 0, false);
        assertEquals(0, state.takeX());
        assertEquals(0, state.takeY());
        assertEquals(0, state.appendEvents(empty(22)).remaining());
        assertEquals(0, state.takeWheel());
        assertFalse(state.buttonSnapshot()[0]);
        boolean[] held = {false, true, false, false, false};
        state.activate(held);
        assertTrue(state.buttonSnapshot()[1]);
    }

    @Test public void queueOverflowFailsOpenInsteadOfLosingARelease() {
        InputState state = active();
        for (int i = 0; i < 4097; i++) state.packet(0, 0, 1, 0, false);
        assertFalse(state.isActive());
        assertEquals(0, state.appendEvents(empty(22)).remaining());
        assertFalse(state.buttonSnapshot()[0]);
    }

    @Test public void concurrentReaderAndProducerDoNotLoseMovement() throws Exception {
        final InputState state = active();
        Thread producer = new Thread(() -> {
            for (int i = 0; i < 100000; i++) state.packet(1, 1, 0, 0, false);
        });
        producer.start();
        int x = 0, y = 0;
        while (producer.isAlive()) { x += state.takeX(); y += state.takeY(); }
        producer.join();
        x += state.takeX(); y += state.takeY();
        assertEquals(100000, x);
        assertEquals(-100000, y);
    }

    private static ByteBuffer empty(int capacity) {
        ByteBuffer buffer = ByteBuffer.allocate(capacity);
        buffer.limit(0);
        return buffer;
    }

    private static void assertEvent(ByteBuffer events, int button, boolean down, int wheel) {
        assertEquals(button, events.get());
        assertEquals(down ? 1 : 0, events.get());
        assertEquals(0, events.getInt());
        assertEquals(0, events.getInt());
        assertEquals(wheel, events.getInt());
        events.getLong(); // nanoTime has an arbitrary (possibly negative) origin.
    }
}
