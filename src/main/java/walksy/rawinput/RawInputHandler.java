package walksy.rawinput;

import com.mojang.blaze3d.platform.Window;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser.WNDCLASSEX;
import com.sun.jna.platform.win32.WinUser.WindowProc;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.apache.logging.log4j.util.TriConsumer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;

/**
 * Handles raw mouse data via the WinUser Raw Input API
 * <p>
 * This class provides unfiltered mouse input independent of GLFW. Operates on a dedicated input thread,
 * preventing mouse data flooding the render thread.
 *
 * <p><strong>Platform Support:</strong> Windows only.
 * <p><strong>Thread Safety:</strong> Delta accumulators and state flags use atomic operations.
 * Callbacks are queued and executed on the main thread
 */
public class RawInputHandler implements AutoCloseable {

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private static final int WM_INPUT = 0x00FF;
    private static final int WM_USER = 0x0400;
    private static final int RID_INPUT = 0x10000003;
    private static final int RIM_TYPEMOUSE = 0;
    private static final int RIDEV_INPUTSINK = 0x00000100;
    private static final int RIDEV_NOLEGACY = 0x00000030;
    private static final int RIDEV_REMOVE = 0x00000001;
    private static final int HEADER_SIZE = 8 + (2 * Native.POINTER_SIZE);
    private static final int FLAGS_OFFSET = HEADER_SIZE;
    private static final int BUTTON_FLAGS_OFFSET = HEADER_SIZE + 4;
    private static final int BUTTON_DATA_OFFSET = HEADER_SIZE + 6;
    private static final int LAST_X_OFFSET = HEADER_SIZE + 12;
    private static final int LAST_Y_OFFSET = HEADER_SIZE + 16;
    private static final int INPUT_BUFFER_SIZE = 256;
    private static final int FOCUS_DELAY = 5;

    private static final double WHEEL_DELTA_UNIT = 120.0;
    private static final int MOUSE_MOVE_ABSOLUTE = 0x01;
    private static final int RI_MOUSE_WHEEL = 0x0400;
    private static final int RI_MOUSE_HWHEEL = 0x0800;

    /** Button flag pairs: {@code [downFlag, upFlag]} for each of the five mouse buttons. */
    private static final int[][] BUTTON_MAP = {
            {0x0001, 0x0002}, {0x0004, 0x0008}, {0x0010, 0x0020}, {0x0040, 0x0080}, {0x0100, 0x0200}
    };

    /** Reusable native memory buffer for {@code GetRawInputData} calls. */
    private final Memory inputBuffer;
    private final IntByReference pcbSize;

    /** Accumulated relative X movement since the last {@link #pollDeltaX()} call. */
    private final AtomicInteger deltaX;

    /** Accumulated relative Y movement since the last {@link #pollDeltaY()} call. */
    private final AtomicInteger deltaY;

    /** Pending main-thread callbacks for button and scroll events. */
    private final ConcurrentLinkedQueue<Runnable> mainThreadEventQueue;

    /** Window procedure for the hidden raw input target window. */
    private final WindowProc rawInputTargetProc;

    private long glfwWindowHandle;

    /** Hidden HWND used as the raw input target. */
    private volatile HWND rawInputTargetHwnd;

    private HWND gameWindowHwnd;
    private volatile boolean legacySuppressed;

    private String windowClassName;
    private TriConsumer<Long, MouseInput, Integer> buttonCallback;
    private TriConsumer<Long, Double, Double> scrollCallback;
    private TriConsumer<Long, Double, Double> cursorCallback;
    private int processedButtons;

    //stupid hack fix to prevent the mouse cursor locking before the window even realises it's focused
    private final AtomicInteger focusTimer;

    private volatile boolean running;
    private volatile boolean windowFocused;

    /** Whether the mouse is grabbed in the focused game window. */
    private volatile boolean gameFocused;

    private volatile int windowCenterX;
    private volatile int windowCenterY;

    public RawInputHandler() {
        this.deltaX = new AtomicInteger(0);
        this.deltaY = new AtomicInteger(0);
        this.running = false;
        this.focusTimer = new AtomicInteger(0);
        this.inputBuffer = new Memory(INPUT_BUFFER_SIZE);
        this.pcbSize = new IntByReference(INPUT_BUFFER_SIZE);
        this.mainThreadEventQueue = new ConcurrentLinkedQueue<>();
        this.rawInputTargetProc = new WindowProc() {
            @Override
            public LRESULT callback(HWND hwnd, int uMsg, WPARAM wParam, LPARAM lParam) {
                return User32.INSTANCE.DefWindowProc(hwnd, uMsg, wParam, lParam);
            }
        };
        this.windowFocused = false;
        this.gameFocused = false;
    }

    /**
     * Initialises raw input and starts the input thread.
     *
     * @param windowHandle the GLFW window handle to associate events with
     * @param buttonAction callback fired on mouse button press/release {@code (window, input, action)}
     * @param scrollAction callback fired on scroll {@code (window, xDelta, yDelta)}
     * @param cursorAction callback for client-area cursor positions {@code (window, x, y)}
     * @return {@code true} if initialisation succeeded; {@code false} on non-Windows or if already running
     */
    public boolean initialize(long windowHandle, TriConsumer<Long, MouseInput, Integer> buttonAction,
                              TriConsumer<Long, Double, Double> scrollAction,
                              TriConsumer<Long, Double, Double> cursorAction) {
        if (!IS_WINDOWS || this.running) {
            return false;
        }

        this.running = true;
        this.glfwWindowHandle = windowHandle;
        this.buttonCallback = buttonAction;
        this.scrollCallback = scrollAction;
        this.cursorCallback = cursorAction;
        this.gameWindowHwnd = new HWND(new Pointer(GLFWNativeWin32.glfwGetWin32Window(windowHandle)));
        this.windowClassName = "RawInputClass_" + this.glfwWindowHandle;

        Thread inputThread = new Thread(this::setupRawInputTarget, "Raw-Input-Buffer-Thread");
        inputThread.setDaemon(true);
        inputThread.start();

        return true;
    }

    /**
     * Registers a hidden window class and runs the raw input message loop.
     * Executes on the dedicated input thread.
     */
    private void setupRawInputTarget() {
        WinDef.HINSTANCE hInstance = Kernel32.INSTANCE.GetModuleHandle(null);

        WNDCLASSEX wClass = new WNDCLASSEX();
        wClass.lpfnWndProc = this.rawInputTargetProc;
        wClass.lpszClassName = this.windowClassName;
        wClass.hInstance = hInstance;
        wClass.cbSize = wClass.size();
        User32.INSTANCE.RegisterClassEx(wClass);

        this.rawInputTargetHwnd = User32.INSTANCE.CreateWindowEx(
                0, this.windowClassName, this.windowClassName + "wnd",
                0, 0, 0, 0, 0, null, null, hInstance, null
        );

        this.setLegacy(this.windowFocused); //this pretty much instantly gets set back to false as the cursor is released on the title screen
        WinUser.MSG msg = new WinUser.MSG();
        while (this.running && User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
            if (msg.message == WM_INPUT) {
                this.handleRawInput(msg.lParam);
            }
            User32.INSTANCE.TranslateMessage(msg);
            User32.INSTANCE.DispatchMessage(msg);
        }

        if (this.rawInputTargetHwnd != null) {
            User32.INSTANCE.DestroyWindow(this.rawInputTargetHwnd);
            this.rawInputTargetHwnd = null;
        }
        User32.INSTANCE.UnregisterClass(this.windowClassName, hInstance);
    }

    /**
     * Toggles no-legacy raw input registration
     *
     * @param exclusive {@code true} to suppress legacy mouse messages; {@code false} for standard sink mode
     */
    public void setLegacy(boolean exclusive) {
        if (this.rawInputTargetHwnd == null) return;

        User32Ex.RAWINPUTDEVICE[] devices = (User32Ex.RAWINPUTDEVICE[]) new User32Ex.RAWINPUTDEVICE().toArray(1);
        devices[0].usUsagePage = 0x01;
        devices[0].usUsage = 0x02;
        devices[0].hwndTarget = this.rawInputTargetHwnd;
        devices[0].dwFlags = exclusive ? (RIDEV_INPUTSINK | RIDEV_NOLEGACY) : RIDEV_INPUTSINK;

        if (User32Ex.INSTANCE.RegisterRawInputDevices(devices, 1, devices[0].size())) {
            this.legacySuppressed = exclusive;
        }
    }

    /**
     * Reads and dispatches a single {@code WM_INPUT} message.
     *
     * @param lParam the {@code LPARAM} from the raw input message, used as the input handle
     */
    private void handleRawInput(LPARAM lParam) {
        if (!this.isBufferingInput() || this.focusTimer.get() > 0) return;

        this.pcbSize.setValue(INPUT_BUFFER_SIZE);
        WinNT.HANDLE hRawInput = new WinNT.HANDLE(new Pointer(lParam.longValue()));

        if (User32Ex.INSTANCE.GetRawInputData(hRawInput, RID_INPUT, this.inputBuffer, this.pcbSize, HEADER_SIZE) > 0) {
            int type = this.inputBuffer.getInt(0);

            if (type == RIM_TYPEMOUSE) {
                short flags = this.inputBuffer.getShort(FLAGS_OFFSET);

                if ((flags & MOUSE_MOVE_ABSOLUTE) == 0) {
                    int lastX = this.inputBuffer.getInt(LAST_X_OFFSET);
                    int lastY = this.inputBuffer.getInt(LAST_Y_OFFSET);

                    if (lastX != 0 || lastY != 0) {
                        if (lastX != 0) {
                            this.deltaX.addAndGet(lastX);
                        }
                        if (lastY != 0) {
                            this.deltaY.addAndGet(lastY);
                        }

                        //prevents the cursor moving in the background
                        //this mainly effects users who player in windowed or have 'auto hide taskbar' enabled via window
                        if (this.gameFocused) {
                            this.centerSystemCursor();
                        }
                    }
                }

                int buttonFlags = this.inputBuffer.getShort(BUTTON_FLAGS_OFFSET) & 0xFFFF;
                short buttonData = this.inputBuffer.getShort(BUTTON_DATA_OFFSET);

                if (buttonFlags != 0) {
                    this.handleButtons(buttonFlags);
                    this.handleScroll(buttonData, buttonFlags);
                }
            }
        }
    }

    /**
     * Queues press and release events for any buttons indicated by {@code buttonFlags}.
     *
     * @param buttonFlags bitmask from the raw input header
     */
    private void handleButtons(int buttonFlags) {
        for (int i = 0; i < BUTTON_MAP.length; i++) {
            if ((buttonFlags & BUTTON_MAP[i][0]) != 0) {
                this.triggerButtonEvent(i, GLFW.GLFW_PRESS);
            }
            if ((buttonFlags & BUTTON_MAP[i][1]) != 0) {
                this.triggerButtonEvent(i, GLFW.GLFW_RELEASE);
            }
        }
    }

    /**
     * Queues a scroll callback if the wheel flag is set.
     *
     * @param buttonData raw wheel delta value
     * @param buttonFlags bitmask from the raw input header
     */
    private void handleScroll(short buttonData, int buttonFlags) {
        if ((buttonFlags & RI_MOUSE_WHEEL) != 0) {
            double normalizedDelta = buttonData / WHEEL_DELTA_UNIT;
            if (this.scrollCallback != null) {
                this.queueAtCursor(() -> this.scrollCallback.accept(this.glfwWindowHandle, 0.0, normalizedDelta));
            }
        }
        if ((buttonFlags & RI_MOUSE_HWHEEL) != 0 && this.scrollCallback != null) {
            double normalizedDelta = -buttonData / WHEEL_DELTA_UNIT;
            this.queueAtCursor(() -> this.scrollCallback.accept(this.glfwWindowHandle, normalizedDelta, 0.0));
        }
    }

    /**
     * Queues a button event for the main thread
     *
     * @param buttonId zero-based button index
     * @param action {@link GLFW#GLFW_PRESS} or {@link GLFW#GLFW_RELEASE}
     */
    private void triggerButtonEvent(int buttonId, int action) {
        if (this.buttonCallback != null) {
            MouseInput input = new MouseInput(buttonId, this.readModifiers());
            this.queueAtCursor(() -> {
                if (action == GLFW.GLFW_PRESS) this.processedButtons |= 1 << buttonId;
                else this.processedButtons &= ~(1 << buttonId);
                this.buttonCallback.accept(this.glfwWindowHandle, input, action);
            });
        }
    }

    private void queueAtCursor(Runnable event) {
        CursorPosition position = this.readCursorPosition();
        this.mainThreadEventQueue.add(() -> {
            this.applyCursorPosition(position);
            event.run();
        });
    }

    /** Samples the OS cursor once per frame instead of forwarding every mouse packet to GLFW. */
    public void updateCursorPosition() {
        this.applyCursorPosition(this.readCursorPosition());
    }

    private void applyCursorPosition(CursorPosition position) {
        if (position != null && this.cursorCallback != null) {
            this.cursorCallback.accept(this.glfwWindowHandle, position.x(), position.y());
        }
    }

    protected CursorPosition readCursorPosition() {
        if (this.gameWindowHwnd == null) return null;
        WinDef.POINT point = new WinDef.POINT();
        // These are the same screen-to-client coordinates used by GLFW on Windows.
        if (!User32.INSTANCE.GetCursorPos(point)
                || !User32Ex.INSTANCE.ScreenToClient(this.gameWindowHwnd, point)) return null;
        return new CursorPosition(point.x, point.y);
    }

    protected record CursorPosition(double x, double y) { }

    /** Capture modifiers with the click, before later keyboard events can change them. */
    protected int readModifiers() {
        int modifiers = 0;
        if (isKeyDown(0x10)) modifiers |= GLFW.GLFW_MOD_SHIFT;
        if (isKeyDown(0x11)) modifiers |= GLFW.GLFW_MOD_CONTROL;
        if (isKeyDown(0x12)) modifiers |= GLFW.GLFW_MOD_ALT;
        if (isKeyDown(0x5B) || isKeyDown(0x5C)) modifiers |= GLFW.GLFW_MOD_SUPER;
        return modifiers;
    }

    private static boolean isKeyDown(int key) {
        return (User32.INSTANCE.GetAsyncKeyState(key) & 0x8000) != 0;
    }

    public boolean isBufferingInput() {
        return this.running && this.windowFocused && this.legacySuppressed;
    }

    /** Keep high-rate legacy events off the main thread in both gameplay and screens. */
    public void updateMouseState(boolean focused, boolean grabbed) {
        this.windowFocused = focused;
        this.gameFocused = focused && grabbed;
        this.setLegacy(focused);
    }

    /** Release delivered presses when focus loss discards the remaining native events. */
    public void releaseButtons() {
        int pressed = this.processedButtons;
        this.processedButtons = 0;
        for (int button = 0; button < BUTTON_MAP.length; button++) {
            if ((pressed & (1 << button)) != 0 && this.buttonCallback != null) {
                this.buttonCallback.accept(this.glfwWindowHandle, new MouseInput(button, 0), GLFW.GLFW_RELEASE);
            }
        }
    }

    /**
     * Drains the pending event queue on the main thread.
     *
     * @param process {@code true} to execute each queued event; {@code false} to discard them
     */
    public void flushEvents(boolean process) {
        Runnable task;
        while ((task = mainThreadEventQueue.poll()) != null) {
            if (process) {
                task.run();
            }
        }
    }

    /**
     * Returns the accumulated X delta and resets the counter to zero.
     *
     * @return relative X movement since the last call
     */
    public double pollDeltaX() {
        return this.deltaX.getAndSet(0);
    }

    /**
     * Returns the accumulated Y delta and resets the counter to zero.
     *
     * @return relative Y movement since the last call
     */
    public double pollDeltaY() {
        return this.deltaY.getAndSet(0);
    }

    /**
     * Resets accumulated deltas
     */
    public void resetDeltas() {
        this.deltaX.set(0);
        this.deltaY.set(0);
    }

    /**
     * @return {@code true} if the input thread is active
     */
    public boolean isRunning() {
        return this.running;
    }

    /**
     * Called via {@link Window} when the game window loses or gains focus
     * <p>
     * Used to schedule a delayed mouse grab on focus gain.
     *
     * @param focused {@code true} if the window gained focus
     */
    public void onWindowFocusChanged(boolean focused) {
        Minecraft client = Minecraft.getInstance();
        if (!focused) {
            this.flushEvents(false);
            this.releaseButtons();
            client.mouseHandler.releaseMouse();
            this.resetDeltas();
            this.gameFocused = false;
            this.windowFocused = false;
            this.setLegacy(false);
        } else {
            this.updateMouseState(true, client.mouseHandler.isMouseGrabbed());
            if (client.screen == null) {
                this.focusTimer.set(FOCUS_DELAY);
            }
        }
    }

    /**
     * Updates focus state and window centre coordinates
     */
    public void tick() {
        Minecraft client = Minecraft.getInstance();
        this.updateMouseState(client.isWindowActive(), client.mouseHandler.isMouseGrabbed());

        Window window = client.getWindow();
        if (window != null) {
            this.windowCenterX = window.getX() + (window.getScreenWidth() / 2);
            this.windowCenterY = window.getY() + (window.getScreenHeight() / 2);
        }

        if (this.focusTimer.get() > 0) {
            if (this.focusTimer.decrementAndGet() == 0) {
                client.execute(client.mouseHandler::grabMouse);
            }
        }
    }

    /**
     * Moves the system cursor to the centre of the Minecraft window.
     */
    private void centerSystemCursor() {
        if (this.windowCenterX != 0 || this.windowCenterY != 0) {
            User32.INSTANCE.SetCursorPos(this.windowCenterX, this.windowCenterY);
        }
    }

    /**
     * Unregisters the raw input device and signals the input thread to terminate.
     */
    @Override
    public void close() {
        if (!IS_WINDOWS || !this.running) return;
        this.running = false;

        if (this.rawInputTargetHwnd != null) {
            User32Ex.RAWINPUTDEVICE[] devices = (User32Ex.RAWINPUTDEVICE[]) new User32Ex.RAWINPUTDEVICE().toArray(1);
            devices[0].usUsagePage = 0x01;
            devices[0].usUsage = 0x02;
            devices[0].dwFlags = RIDEV_REMOVE;
            devices[0].hwndTarget = null;
            User32Ex.INSTANCE.RegisterRawInputDevices(devices, 1, devices[0].size());

            User32.INSTANCE.PostMessage(this.rawInputTargetHwnd, WM_USER, new WPARAM(0), new LPARAM(0));
        }
    }

    /**
     * JNA binding for {@code user32.dll} functions not exposed by the standard JNA platform library.
     */
    private interface User32Ex extends StdCallLibrary {
        User32Ex INSTANCE = Native.load("user32", User32Ex.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean ScreenToClient(HWND hwnd, WinDef.POINT point);

        /**
         * Descriptor passed to {@link #RegisterRawInputDevices}.
         */
        @Structure.FieldOrder({"usUsagePage", "usUsage", "dwFlags", "hwndTarget"})
        class RAWINPUTDEVICE extends Structure {
            public short usUsagePage;
            public short usUsage;
            public int dwFlags;
            public HWND hwndTarget;
        }

        /**
         * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-registerrawinputdevices">MSDN</a>
         */
        boolean RegisterRawInputDevices(RAWINPUTDEVICE[] pRawInputDevices, int uiNumDevices, int cbSize);

        /**
         * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getrawinputdata">MSDN</a>
         */
        int GetRawInputData(WinNT.HANDLE hRawInput, int uiCommand, Pointer pData, IntByReference pcbSize, int cbSizeHeader);
    }
}
