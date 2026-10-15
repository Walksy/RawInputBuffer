package walksy.rawinput;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Windows Raw Input reader, adapted from Walksy's original handler. */
final class RawInputHandler implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger("RawInputBuffer");
    private static final int WM_INPUT = 0x00ff;
    private static final int WM_TIMER = 0x0113;
    private static final int WM_WAKE = 0x8001;
    private static final int HEADER_SIZE = 8 + 2 * Native.POINTER_SIZE;
    private static final int[] VIRTUAL_BUTTONS = {1, 2, 4, 5, 6};
    final InputState state = new InputState();
    private Pointer gameWindow;
    private final CountDownLatch ready = new CountDownLatch(1);
    private final Memory buffer = new Memory(256);
    private final IntByReference size = new IntByReference(256);
    private final Win32.WindowProc windowProc = this::windowMessage;
    private final Win32.Point center = new Win32.Point();
    private final Win32.Rect rect = new Win32.Rect();
    private volatile Pointer target;
    private volatile boolean running;
    private boolean registered;
    private boolean failed;
    private Thread thread;

    RawInputHandler(long hwnd) { gameWindow = new Pointer(hwnd); }

    synchronized boolean updateWindow(long hwnd) {
        Pointer next = new Pointer(hwnd);
        if (gameWindow.equals(next)) return false;
        setCaptured(false);
        gameWindow = next;
        return true;
    }

    boolean start() throws InterruptedException {
        running = true;
        thread = new Thread(this::run, "Raw-Input-Buffer-Thread");
        thread.setDaemon(true);
        thread.start();
        if (!ready.await(3, TimeUnit.SECONDS) || target == null || !running) {
            close();
            return false;
        }
        return true;
    }

    private void run() {
        WString className = new WString("RawInputBuffer_" + System.nanoTime());
        Pointer instance = Win32.Kernel32.INSTANCE.GetModuleHandleW(null);
        boolean classRegistered = false;
        try {
            Win32.WindowClass cls = new Win32.WindowClass();
            cls.lpfnWndProc = windowProc;
            cls.lpszClassName = className;
            cls.hInstance = instance;
            cls.cbSize = cls.size();
            if (Win32.User32.INSTANCE.RegisterClassExW(cls) == 0) throw nativeError("RegisterClassEx");
            classRegistered = true;
            target = Win32.User32.INSTANCE.CreateWindowExW(0, className, className, 0,
                0, 0, 0, 0, null, null, instance, null);
            if (target == null) throw nativeError("CreateWindowEx");
            if (Win32.User32.INSTANCE.SetTimer(target, new Win32.Word(1), 10, null).longValue() == 0) {
                throw nativeError("SetTimer");
            }
            ready.countDown();
            Memory message = new Memory(64); // enough for MSG on both Win32 and Win64
            while (running) {
                int result = Win32.User32.INSTANCE.GetMessageW(message, null, 0, 0);
                if (result < 0) throw nativeError("GetMessage");
                if (result == 0) break;
                Win32.User32.INSTANCE.TranslateMessage(message);
                Win32.User32.INSTANCE.DispatchMessageW(message);
            }
        } catch (Throwable error) {
            LOG.log(Level.WARNING, "Raw input stopped; restoring the standard mouse", error);
        } finally {
            running = false;
            synchronized (this) {
                unregister();
                state.deactivate();
            }
            if (target != null) {
                Win32.User32.INSTANCE.KillTimer(target, new Win32.Word(1));
                Win32.User32.INSTANCE.DestroyWindow(target);
                target = null;
            }
            if (classRegistered) Win32.User32.INSTANCE.UnregisterClassW(className, instance);
            ready.countDown();
        }
    }

    private Win32.Word windowMessage(Pointer hwnd, int message, Win32.Word wp, Win32.Word lp) {
        try {
            if (message == WM_INPUT) readPacket(lp);
            if (message == WM_TIMER) {
                synchronized (this) {
                    if (registered && (!state.isActive() || !isForeground())) {
                        if (!state.isActive()) failed = true;
                        setCaptured(false);
                    }
                    if (registered) centerCursor();
                }
            }
        } catch (Throwable error) {
            LOG.log(Level.WARNING, "Raw input callback failed; restoring the standard mouse", error);
            synchronized (this) { failed = true; setCaptured(false); }
        }
        // DefWindowProc cleans up foreground WM_INPUT resources.
        return Win32.User32.INSTANCE.DefWindowProcW(hwnd, message, wp, lp);
    }

    private synchronized void readPacket(Win32.Word lp) {
        if (!registered || !state.isActive()) return;
        size.setValue(256);
        int bytes = Win32.User32.INSTANCE.GetRawInputData(new Pointer(lp.longValue()),
            0x10000003, buffer, size, HEADER_SIZE);
        if (bytes == -1) throw nativeError("GetRawInputData");
        if (bytes < HEADER_SIZE + 24 || buffer.getInt(0) != 0) return;
        state.packet(buffer.getInt(HEADER_SIZE + 12), buffer.getInt(HEADER_SIZE + 16),
            buffer.getShort(HEADER_SIZE + 4) & 0xffff, buffer.getShort(HEADER_SIZE + 6),
            (buffer.getShort(HEADER_SIZE) & 1) != 0);
    }

    synchronized boolean setCaptured(boolean capture) {
        if (registered && !state.isActive()) failed = true;
        capture = capture && running && !failed && target != null && isForeground();
        if (capture == registered) return registered;
        if (!capture) {
            unregister();
            state.deactivate();
            return false;
        }
        state.activate(physicalButtons());
        Win32.RawDevice device = new Win32.RawDevice();
        device.dwFlags = 0x0100 | 0x0030; // INPUTSINK | NOLEGACY, only during gameplay
        device.hwndTarget = target;
        if (!Win32.User32.INSTANCE.RegisterRawInputDevices(device, 1, device.size())) {
            state.deactivate();
            failed = true;
            LOG.warning("RegisterRawInputDevices failed: " + Native.getLastError());
            return false;
        }
        registered = true;
        return true;
    }

    private void unregister() {
        if (!registered) return;
        Win32.RawDevice device = new Win32.RawDevice();
        device.dwFlags = 1; // REMOVE requires a null target
        if (!Win32.User32.INSTANCE.RegisterRawInputDevices(device, 1, device.size())) {
            LOG.warning("Raw input removal failed: " + Native.getLastError());
        }
        registered = false;
    }

    boolean[] physicalButtons() {
        boolean[] held = new boolean[5];
        for (int i = 0; i < held.length; i++) {
            held[i] = (Win32.User32.INSTANCE.GetAsyncKeyState(VIRTUAL_BUTTONS[i]) & 0x8000) != 0;
        }
        return held;
    }

    private boolean isForeground() {
        return gameWindow.equals(Win32.User32.INSTANCE.GetForegroundWindow());
    }

    private void centerCursor() {
        if (Win32.User32.INSTANCE.GetClientRect(gameWindow, rect)) {
            center.x = (rect.right - rect.left) / 2;
            center.y = (rect.bottom - rect.top) / 2;
            if (Win32.User32.INSTANCE.ClientToScreen(gameWindow, center)) {
                Win32.User32.INSTANCE.SetCursorPos(center.x, center.y);
            }
        }
    }

    private static IllegalStateException nativeError(String operation) {
        return new IllegalStateException(operation + " failed: Win32 error " + Native.getLastError());
    }

    @Override public void close() {
        synchronized (this) {
            running = false;
            unregister();
            state.deactivate();
            if (target != null) Win32.User32.INSTANCE.PostMessageW(target, WM_WAKE,
                new Win32.Word(0), new Win32.Word(0));
        }
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(2000); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
    }
}
