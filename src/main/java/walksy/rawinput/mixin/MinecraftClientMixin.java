package walksy.rawinput.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import walksy.rawinput.RawInput;
import walksy.rawinput.RawInputHandler;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Inject(method = "onWindowFocusChanged", at = @At("TAIL"))
    public void onWindowFocusChanged(boolean focused, CallbackInfo ci) {
        RawInputHandler inputHandler = RawInput.getInputHandler();
        if (inputHandler.isRunning()) {
            inputHandler.onWindowFocusChanged(focused);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    public void tick(CallbackInfo ci) {
        RawInputHandler inputHandler = RawInput.getInputHandler();
        if (inputHandler.isRunning()) {
            inputHandler.tick();
        }
    }
}
