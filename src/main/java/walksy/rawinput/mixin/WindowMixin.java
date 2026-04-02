package walksy.rawinput.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import walksy.rawinput.RawInput;
import walksy.rawinput.RawInputHandler;

@Mixin(Window.class)
public class WindowMixin {

    @Inject(method = "onFocus(JZ)V", at = @At("TAIL"))
    private void onFocusChanged(long handle, boolean focused, CallbackInfo ci) {
        RawInputHandler inputHandler = RawInput.getInputHandler();
        if (inputHandler.isRunning()) {
            inputHandler.onWindowFocusChanged(focused);
        }
    }
}