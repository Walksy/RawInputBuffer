package walksy.rawinput;

import com.sun.jna.IntegerType;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.Arrays;
import java.util.List;

/** JNA 3.4.0 ships with Minecraft 1.8.9; no replacement JNA or DLL is bundled. */
public final class Win32 {
    private Win32() { }
    public static class Word extends IntegerType {
        public Word() { this(0); }
        public Word(long value) { super(Native.POINTER_SIZE, value); }
    }
    public interface WindowProc extends StdCallLibrary.StdCallCallback {
        Word callback(Pointer hwnd, int message, Word wParam, Word lParam);
    }
    public interface User32 extends StdCallLibrary {
        User32 INSTANCE = (User32) Native.loadLibrary("user32", User32.class);
        short RegisterClassExW(WindowClass windowClass);
        Pointer CreateWindowExW(int exStyle, WString className, WString title, int style,
            int x, int y, int width, int height, Pointer parent, Pointer menu,
            Pointer instance, Pointer param);
        Word DefWindowProcW(Pointer hwnd, int message, Word wParam, Word lParam);
        int GetMessageW(Pointer message, Pointer hwnd, int min, int max);
        boolean TranslateMessage(Pointer message);
        Word DispatchMessageW(Pointer message);
        boolean PostMessageW(Pointer hwnd, int message, Word wParam, Word lParam);
        boolean DestroyWindow(Pointer hwnd);
        boolean UnregisterClassW(WString name, Pointer instance);
        boolean RegisterRawInputDevices(RawDevice device, int count, int size);
        int GetRawInputData(Pointer input, int command, Pointer data,
                            IntByReference size, int headerSize);
        Pointer GetForegroundWindow();
        short GetAsyncKeyState(int key);
        Word SetTimer(Pointer hwnd, Word id, int interval, Pointer callback);
        boolean KillTimer(Pointer hwnd, Word id);
        boolean GetClientRect(Pointer hwnd, Rect rect);
        boolean ClientToScreen(Pointer hwnd, Point point);
        boolean SetCursorPos(int x, int y);
    }
    public interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = (Kernel32) Native.loadLibrary("kernel32", Kernel32.class);
        Pointer GetModuleHandleW(WString name);
    }
    public static class WindowClass extends Structure {
        public int cbSize;
        public int style;
        public WindowProc lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public Pointer hInstance;
        public Pointer hIcon;
        public Pointer hCursor;
        public Pointer hbrBackground;
        public WString lpszMenuName;
        public WString lpszClassName;
        public Pointer hIconSm;
        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("cbSize", "style", "lpfnWndProc", "cbClsExtra", "cbWndExtra",
                "hInstance", "hIcon", "hCursor", "hbrBackground", "lpszMenuName",
                "lpszClassName", "hIconSm");
        }
    }
    public static class RawDevice extends Structure {
        public short usUsagePage = 1;
        public short usUsage = 2;
        public int dwFlags;
        public Pointer hwndTarget;
        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("usUsagePage", "usUsage", "dwFlags", "hwndTarget");
        }
    }
    public static class Point extends Structure {
        public int x;
        public int y;
        @Override protected List<String> getFieldOrder() { return Arrays.asList("x", "y"); }
    }
    public static class Rect extends Structure {
        public int left;
        public int top;
        public int right;
        public int bottom;
        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("left", "top", "right", "bottom");
        }
    }
}
