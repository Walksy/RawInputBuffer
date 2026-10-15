package walksy.rawinput;

import com.sun.jna.Native;
import java.util.Locale;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

public class WindowsNativeTest {
    @Test public void win32StructureLayoutsMatchTheNativeAbi() {
        windows();
        assertEquals(Native.POINTER_SIZE == 8 ? 80 : 48, new Win32.WindowClass().size());
        assertEquals(Native.POINTER_SIZE == 8 ? 16 : 12, new Win32.RawDevice().size());
        assertEquals(8, new Win32.Point().size());
        assertEquals(16, new Win32.Rect().size());
        assertNotNull(Win32.Kernel32.INSTANCE.GetModuleHandleW(null));
    }

    @Test public void createsHiddenWindowAndStopsBlockedMessageLoop() throws Exception {
        windows();
        for (int i = 0; i < 3; i++) {
            RawInputHandler handler = new RawInputHandler(0);
            try {
                assertTrue("Hidden window must initialize", handler.start());
                assertFalse("No foreground game window: must not capture", handler.setCaptured(true));
                assertFalse(handler.state.isActive());
            } finally { handler.close(); }
        }
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            assertFalse("Native message loop must exit", thread.isAlive()
                && thread.getName().equals("Raw-Input-Buffer-Thread"));
        }
    }

    private static void windows() {
        Assume.assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"));
    }
}
