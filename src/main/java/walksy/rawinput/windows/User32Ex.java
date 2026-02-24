package walksy.rawinput.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef.HWND;

public class User32Ex {
    //User32Ex INSTANCE = Native.load("user32", User32Ex.class, W32APIOptions.DEFAULT_OPTIONS);
    static {
        Native.register("user32");
    }

    @Structure.FieldOrder({"usUsagePage", "usUsage", "dwFlags", "hwndTarget"})
    public static class RAWINPUTDEVICE extends Structure {
        public short usUsagePage;
        public short usUsage;
        public int dwFlags;
        public HWND hwndTarget;

        public RAWINPUTDEVICE() { super(); }
    }

    public static native boolean RegisterRawInputDevices(Pointer pRawInputDevices, int uiNumDevices, int cbSize);
    public static native int GetRawInputData(long hRawInput, int uiCommand, Pointer pData, int[] pcbSize, int cbSizeHeader);
}