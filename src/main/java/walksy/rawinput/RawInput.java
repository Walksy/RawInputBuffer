package walksy.rawinput;

import net.fabricmc.api.ModInitializer;

public class RawInput implements ModInitializer {

    private static RawInputHandler inputHandler;

    @Override
    public void onInitialize() {
        inputHandler = new RawInputHandler();
    }

    public static RawInputHandler getInputHandler() {
        return inputHandler;
    }
}
