package walksy.rawinput.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import walksy.rawinput.RawInput;
import walksy.rawinput.RawInputHandler;

/** Flashback's ImGui backend needs native GLFW events, including when no Screen is open. */
@Pseudo
@Mixin(targets = "com.moulberry.flashback.editor.ui.ReplayUI", remap = false)
public abstract class FlashbackReplayUIMixin {
    @Inject(method = "transitionActiveState(Z)V", at = @At("HEAD"), remap = false)
    private static void rawinput$onEditorStateChanged(boolean active, CallbackInfo ci) {
        RawInputHandler handler = RawInput.getInputHandler();
        if (handler != null) handler.setExternalMouseOwner(active);
    }
}
