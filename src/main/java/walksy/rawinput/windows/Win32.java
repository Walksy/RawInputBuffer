package walksy.rawinput.windows;

/**
 * Constant definitions for the Windows Raw Input API and HID usages.
 * These values define the protocol used to communicate with the Windows kernel
 * and interpret the bitmasks returned by mouse hardware
 */
public class Win32 {

    /** Windows message sent to the target window when raw input is available. */
    public static final int WM_INPUT = 0x00FF;

    /** Command flag for {@code GetRawInputData} to retrieve the actual data packet. */
    public static final int RID_INPUT = 0x10000003;

    /** Identifies that the raw input originated from a mouse device. */
    public static final int RIM_TYPEMOUSE = 0;

    /** HID usage page for generic desktop controls. */
    public static final int HID_USAGE_PAGE_GENERIC = 0x01;

    /** HID usage ID for a mouse device. */
    public static final int HID_USAGE_MOUSE = 0x02;

    /**
     * Flag allowing the window to receive input even if it is not currently in the foreground.
     * Essential for maintaining input consistency in windowed or borderless modes.
     */
    public static final int RIDEV_INPUTSINK = 0x00000100;

    /**
     * Disables "Legacy" messages (like {@code WM_MOUSEMOVE}).
     * Prevents the Windows kernel from wasting CPU cycles generating standard cursor events.
     */
    public static final int RIDEV_NOLEGACY  = 0x00000030;

    /** Removes the raw input registration for the specified device. Used during cleanup. */
    public static final int RIDEV_REMOVE = 0x00000001;

    /**
     * Bitmask indicating the mouse coordinates are absolute (for example touch screen).
     * If this bit is NOT set, the movement is relative (a normal mouse lol).
     */
    public static final int MOUSE_MOVE_ABSOLUTE = 0x01;

    /** Flag indicating that the mouse wheel was rotated. */
    public static final int RI_MOUSE_WHEEL = 0x0400;

    /** Mask used to isolate button and wheel data from the {@code ulButtons} field. */
    public static final int BUTTON_FLAGS_MASK = 0xFFFF;

    /**
     * Bit-shift value to extract the signed wheel delta from the upper 16 bits
     * of the raw button data.
     */
    public static final int WHEEL_DATA_SHIFT = 16;

    /**
     * The default Windows unit for one "notch" of the scroll wheel.
     * Values are divided by this to normalize scroll speed across different hardware.
     */
    public static final double WHEEL_DELTA_UNIT = 120.0;

    //Internal Win32 Button state flags
    private static final int RI_MOUSE_LEFT_BUTTON_DOWN = 0x0001;
    private static final int RI_MOUSE_LEFT_BUTTON_UP = 0x0002;
    private static final int RI_MOUSE_RIGHT_BUTTON_DOWN = 0x0004;
    private static final int RI_MOUSE_RIGHT_BUTTON_UP = 0x0008;
    private static final int RI_MOUSE_MIDDLE_BUTTON_DOWN = 0x0010;
    private static final int RI_MOUSE_MIDDLE_BUTTON_UP = 0x0020;
    private static final int RI_MOUSE_BUTTON_4_DOWN = 0x0040;
    private static final int RI_MOUSE_BUTTON_4_UP = 0x0080;
    private static final int RI_MOUSE_BUTTON_5_DOWN = 0x0100;
    private static final int RI_MOUSE_BUTTON_5_UP = 0x0200;

    /**
     * A mapping table used to iterate through mouse buttons.
     * Each entry contains the [DownFlag, UpFlag] for buttons 0 through 4.
     */
    public static final int[][] BUTTON_MAP = {
            {RI_MOUSE_LEFT_BUTTON_DOWN, RI_MOUSE_LEFT_BUTTON_UP},
            {RI_MOUSE_RIGHT_BUTTON_DOWN, RI_MOUSE_RIGHT_BUTTON_UP},
            {RI_MOUSE_MIDDLE_BUTTON_DOWN, RI_MOUSE_MIDDLE_BUTTON_UP},
            {RI_MOUSE_BUTTON_4_DOWN, RI_MOUSE_BUTTON_4_UP},
            {RI_MOUSE_BUTTON_5_DOWN, RI_MOUSE_BUTTON_5_UP}
    };
}