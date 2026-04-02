package walksy.rawinput.mixin;

import org.apache.logging.log4j.util.TriConsumer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWDropCallback;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import walksy.rawinput.MouseInput;
import walksy.rawinput.RawInput;
import walksy.rawinput.RawInputHandler;
import com.mojang.blaze3d.platform.Window;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;

@Mixin(MouseHandler.class)
public abstract class MouseMixin {

    @Shadow private double accumulatedDY;
    @Shadow private double accumulatedDX;
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private static Logger LOGGER;
    @Shadow protected abstract void onScroll(long window, double horizontal, double vertical);
    @Shadow protected abstract void onMove(long window, double x, double y);
    @Shadow protected abstract void onDrop(long window, List<Path> paths, int invalidFilesCount);
    @Shadow protected abstract void onButton(long window, net.minecraft.client.input.MouseButtonInfo input, int action);

    @Unique
    private final TriConsumer<Long, MouseInput, Integer> MOUSE_BUTTON_CALLBACK = (windowx, input, action) -> {
        this.onButton(windowx, new net.minecraft.client.input.MouseButtonInfo(input.button(), input.modifiers()), action);
    };

    @Unique
    private final TriConsumer<Long, Double, Double> MOUSE_SCROLL_CALLBACK = this::onScroll;

    @Inject(method = "setup", at = @At("HEAD"), cancellable = true)
    private void setup(Window window, CallbackInfo ci) {
        RawInputHandler inputHandler = RawInput.getInputHandler();
        if (inputHandler.initialize(window.handle(), MOUSE_BUTTON_CALLBACK, MOUSE_SCROLL_CALLBACK)) {
            this.setupGlfw(window.handle());
            ci.cancel();
        }
    }

    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    public void tick(CallbackInfo ci) {
        RawInputHandler inputHandler = RawInput.getInputHandler();
        if (inputHandler.isRunning()) {
            inputHandler.flushEvents(!this.shouldProcessGlfw());

            if (!this.shouldProcessGlfw()) {
                this.accumulatedDX = inputHandler.pollDeltaX();
                this.accumulatedDY = inputHandler.pollDeltaY();
            }
        }
    }

    @Inject(method = "grabMouse", at = @At("HEAD"))
    public void onLockCursor(CallbackInfo ci) {
        RawInputHandler inputHandler = RawInput.getInputHandler();
        if (inputHandler.isRunning()) {
            inputHandler.setLegacy(true);
        }
    }

    @Inject(method = "releaseMouse", at = @At("HEAD"))
    public void onUnlockCursor(CallbackInfo ci) {
        RawInputHandler inputHandler = RawInput.getInputHandler();
        if (inputHandler.isRunning()) {
            inputHandler.setLegacy(false);
        }
    }

    @Unique
    private void setupGlfw(long window) {
        GLFW.glfwSetCursorPosCallback(window, (windowx, x, y) -> {
            if (this.shouldProcessGlfw()) {
                this.minecraft.execute(() -> this.onMove(windowx, x, y));
            }
        });

        GLFW.glfwSetMouseButtonCallback(window, (windowx, button, action, modifiers) -> {
            if (this.shouldProcessGlfw()) {
                this.minecraft.execute(() -> this.onButton(windowx, new net.minecraft.client.input.MouseButtonInfo(button, modifiers), action));
            }
        });

        GLFW.glfwSetScrollCallback(window, (windowx, offsetX, offsetY) -> {
            if (this.shouldProcessGlfw()) {
                this.minecraft.execute(() -> this.onScroll(windowx, offsetX, offsetY));
            }
        });

        GLFW.glfwSetDropCallback(window, (windowx, count, names) -> {
            List<Path> list = new ArrayList<>(count);
            int i = 0;
            for(int j = 0; j < count; ++j) {
                String string = GLFWDropCallback.getName(names, j);
                try {
                    list.add(Paths.get(string));
                } catch (InvalidPathException invalidPathException) {
                    ++i;
                    LOGGER.error("Failed to parse path '{}'", string, invalidPathException);
                }
            }
            if (!list.isEmpty()) {
                int finalI = i;
                this.minecraft.execute(() -> this.onDrop(windowx, list, finalI));
            }
        });
    }

    @Unique
    private boolean shouldProcessGlfw() {
        return this.minecraft.screen != null;
    }
}