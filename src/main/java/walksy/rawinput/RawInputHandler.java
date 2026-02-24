package walksy.rawinput;

import com.sun.jna.Memory;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.WinUser.WNDCLASSEX;
import com.sun.jna.platform.win32.WinUser.WindowProc;
import net.minecraft.client.input.MouseInput;
import org.apache.logging.log4j.util.TriConsumer;
import org.lwjgl.glfw.GLFW;
import walksy.rawinput.windows.User32Ex;
import walksy.rawinput.windows.Win32;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles asynchronous Raw Input from Windows to bypass standard GLFW input latency
 * Runs on a dedicated background thread
 */
public class RawInputHandler implements AutoCloseable {
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final long INPUT_BUFFER_SIZE = 256;
    private static final byte RAW_INPUT_HEADER_SIZE = 24;

    private final Memory inputBuffer;
    private final int[] pcbSize = new int[1];

    /**
     * Using AtomicIntegers prevents thread contention through state locks at high polling rates
     */
    private final AtomicInteger deltaX;
    private final AtomicInteger deltaY;
    private final ConcurrentLinkedQueue<Runnable> mainThreadEventQueue;

    private long glfwWindowHandle;
    private HWND rawInputTargetHwnd;
    private TriConsumer<Long, MouseInput, Integer> buttonCallback;
    private TriConsumer<Long, Double, Double> scrollCallback;
    private volatile boolean running;

    /** Safe reference to the native Window Procedure callback to prevent Garbage Collection crashes */
    private final WindowProc rawInputTargetProc = User32.INSTANCE::DefWindowProc;

    public RawInputHandler() {
        this.deltaX = new AtomicInteger(0);
        this.deltaY = new AtomicInteger(0);
        this.running = false;
        this.inputBuffer = new Memory(INPUT_BUFFER_SIZE);
        this.mainThreadEventQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * Starts the asynchronous input thread and hooks into the Windows Raw Input API
     * @param windowHandle The GLFW window handle
     * @param buttonAction Consumer for mouse button events
     * @param scrollAction Consumer for scroll wheel events
     * @return true if initialization was successful
     */
    public boolean initialize(long windowHandle, TriConsumer<Long, MouseInput, Integer> buttonAction, TriConsumer<Long, Double, Double> scrollAction) {
        if (!IS_WINDOWS || this.running) {
            return false;
        }

        this.running = true;
        this.glfwWindowHandle = windowHandle;
        this.buttonCallback = buttonAction;
        this.scrollCallback = scrollAction;

        Thread inputThread = new Thread(this::setupRawInputTarget, "Raw-Input-Buffer-Thread");
        inputThread.setDaemon(true);
        inputThread.start();
        return true;
    }

    /**
     * Registers a hidden window class and starts the Win32 message loop
     * which allows the mod to receive mouse data without interfering with GLFW
     */
    private void setupRawInputTarget() {
        String className = this.glfwWindowHandle + "_";
        WNDCLASSEX wClass = new WNDCLASSEX();
        wClass.lpfnWndProc = this.rawInputTargetProc;
        wClass.lpszClassName = className;
        User32.INSTANCE.RegisterClassEx(wClass);

        this.rawInputTargetHwnd = User32.INSTANCE.CreateWindowEx(
                0, className, className + "wnd", 0, 0, 0, 0, 0, null, null, null, null
        );

        this.setLegacy(true);

        WinUser.MSG msg = new WinUser.MSG();
        while (User32.INSTANCE.GetMessage(msg, this.rawInputTargetHwnd, 0, 0) != 0) {
            if (msg.message == Win32.WM_INPUT) {
                this.handleRawInput(msg.lParam);
            } else {
                User32.INSTANCE.TranslateMessage(msg);
                User32.INSTANCE.DispatchMessage(msg);
            }
        }
    }

    /**
     * Configures whether Windows should suppress standard mouse messages.
     * @param exclusive If true, {@code RIDEV_NOLEGACY} is used to kill OS-level cursor processing
     *                  which reduces CPU usage with high polling rates
     */
    public void setLegacy(boolean exclusive) {
        if (this.rawInputTargetHwnd == null) return;

        User32Ex.RAWINPUTDEVICE device = new User32Ex.RAWINPUTDEVICE();
        device.usUsagePage = Win32.HID_USAGE_PAGE_GENERIC;
        device.usUsage = Win32.HID_USAGE_MOUSE;
        device.hwndTarget = rawInputTargetHwnd;
        device.dwFlags = exclusive ? (Win32.RIDEV_INPUTSINK | Win32.RIDEV_NOLEGACY) : Win32.RIDEV_INPUTSINK;

        device.write();
        User32Ex.RegisterRawInputDevices(device.getPointer(), 1, device.size());
    }

    /**
     * Reads the raw memory buffer from Windows and extracts mouse movement and button states
     * @param lParam The Win32 pointer to the raw input data
     */
    private void handleRawInput(LPARAM lParam) {
        if (!this.running) return;
        pcbSize[0] = (int) this.inputBuffer.size();

        if (User32Ex.GetRawInputData(lParam.longValue(), Win32.RID_INPUT, this.inputBuffer, pcbSize, RAW_INPUT_HEADER_SIZE) > 0) {
            int type = this.inputBuffer.getInt(0); //dwType

            if (type == Win32.RIM_TYPEMOUSE) {
                short flags = this.inputBuffer.getShort(24);

                if ((flags & Win32.MOUSE_MOVE_ABSOLUTE) == 0) {
                    int lastX = this.inputBuffer.getInt(36); //lLastX
                    int lastY = this.inputBuffer.getInt(40); //lLastY

                    if (lastX != 0) {
                        this.deltaX.addAndGet(lastX);
                    }

                    if (lastY != 0) {
                        this.deltaY.addAndGet(lastY);
                    }
                }

                int buttonData = this.inputBuffer.getInt(28); //ulButtons
                int buttonFlags = buttonData & Win32.BUTTON_FLAGS_MASK;

                if (buttonFlags != 0) {
                    this.handleButtons(buttonFlags);
                    this.handleScroll(buttonData, buttonFlags);
                }
            }
        }
    }

    /**
     * Maps raw button state flags to GLFW actions and schedules them for the main thread.
     * @param buttonFlags The bitmask of mouse button states
     */
    private void handleButtons(int buttonFlags) {
        for (short i = 0; i < Win32.BUTTON_MAP.length; i++) {
            if ((buttonFlags & Win32.BUTTON_MAP[i][0]) != 0) {
                this.triggerButtonEvent(i, GLFW.GLFW_PRESS);
            } else if ((buttonFlags & Win32.BUTTON_MAP[i][1]) != 0) {
                this.triggerButtonEvent(i, GLFW.GLFW_RELEASE);
            }
        }
    }

    /**
     * Extracts and normalizes scroll wheel delta
     * @param buttonData The raw button data containing scroll information
     * @param buttonFlags
     */
    private void handleScroll(int buttonData, int buttonFlags) {
        if ((buttonFlags & Win32.RI_MOUSE_WHEEL) != 0) {
            short wheelDelta = (short) (buttonData >> Win32.WHEEL_DATA_SHIFT);
            double normalizedDelta = (double) wheelDelta / Win32.WHEEL_DELTA_UNIT;
            if (this.scrollCallback != null) {
                this.mainThreadEventQueue.add(() -> this.scrollCallback.accept(this.glfwWindowHandle, 0.0, normalizedDelta));
            }
        }
    }

    /**
     * Pushes button events to the Minecraft main thread via the callback
     */
    private void triggerButtonEvent(int buttonId, int action) {
        if (this.buttonCallback != null) {
            this.mainThreadEventQueue.add(() -> this.buttonCallback.accept(this.glfwWindowHandle, new MouseInput(buttonId, 0), action));
        }
    }

    /**
     * Executes all queued button and scroll events to Minecraft's main thread
     */
    public void processQueuedEvents() {
        Runnable task;
        while ((task = mainThreadEventQueue.poll()) != null) {
            task.run();
        }
    }

    /**
     * Returns and resets the accumulated horizontal movement
     */
    public double pollDeltaX() {
        if (this.deltaX.get() != 0) {
            return this.deltaX.getAndSet(0);
        }
        return 0.0;
    }

    /**
     * Returns and resets the accumulated vertical movement
     */
    public double pollDeltaY() {
        if (this.deltaY.get() != 0) {
            return this.deltaY.getAndSet(0);
        }
        return 0.0;
    }


    public boolean isRunning() {
        return this.running;
    }

    /**
     * Cleans up the native window, unregisters the raw input device, and shuts down the handler
     */
    @Override
    public void close() {
        if (!IS_WINDOWS) return;
        this.running = false;
        if (this.rawInputTargetHwnd != null) {
            User32Ex.RAWINPUTDEVICE device = new User32Ex.RAWINPUTDEVICE();
            device.usUsagePage = Win32.HID_USAGE_PAGE_GENERIC;
            device.usUsage = Win32.HID_USAGE_MOUSE;
            device.dwFlags = Win32.RIDEV_REMOVE;
            device.hwndTarget = null;

            device.write();
            User32Ex.RegisterRawInputDevices(device.getPointer(), 1, device.size());

            User32.INSTANCE.PostMessage(this.rawInputTargetHwnd, WinUser.WM_CLOSE, null, null);
            User32.INSTANCE.DestroyWindow(this.rawInputTargetHwnd);
            this.rawInputTargetHwnd = null;
        }

        if (this.glfwWindowHandle != 0) {
            User32.INSTANCE.UnregisterClass(glfwWindowHandle + "_", null);
        }
    }
}