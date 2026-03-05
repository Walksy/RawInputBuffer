package walksy.rawinput.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.util.Window;
import net.minecraft.text.Text;
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

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Mixin(Mouse.class)
public abstract class MouseMixin {

    @Shadow private double cursorDeltaY;
    @Shadow private double cursorDeltaX;
    @Shadow @Final private MinecraftClient client;
    @Shadow @Final private static Logger LOGGER;
    @Shadow protected abstract void onMouseScroll(long window, double horizontal, double vertical);
    @Shadow protected abstract void onCursorPos(long window, double x, double y);
    @Shadow protected abstract void onFilesDropped(long window, List<Path> paths, int invalidFilesCount);
    @Shadow protected abstract void onMouseButton(long window, net.minecraft.client.input.MouseInput input, int action);

    @Unique
    private final TriConsumer<Long, MouseInput, Integer> MOUSE_BUTTON_CALLBACK = (windowx, input, action) -> {
        this.onMouseButton(windowx, new net.minecraft.client.input.MouseInput(input.button(), input.modifiers()), action);
    };

    @Unique
    private final TriConsumer<Long, Double, Double> MOUSE_SCROLL_CALLBACK = this::onMouseScroll;

    @Inject(method = "setup", at = @At("HEAD"), cancellable = true)
    private void setup(Window window, CallbackInfo ci) {
        RawInputHandler inputHandler = RawInput.getInputHandler();
        if (inputHandler.initialize(window.getHandle(), MOUSE_BUTTON_CALLBACK, MOUSE_SCROLL_CALLBACK)) {
            this.setupGlfw(window.getHandle());
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    public void tick(CallbackInfo ci) {
        RawInputHandler inputHandler = RawInput.getInputHandler();
        if (inputHandler.isRunning()) {
            inputHandler.flushEvents(!this.shouldProcessGlfw());

            this.cursorDeltaX = inputHandler.pollDeltaX();
            this.cursorDeltaY = inputHandler.pollDeltaY();
        }
    }

    @Inject(method = "lockCursor", at = @At("HEAD"))
    public void onLockCursor(CallbackInfo ci) {
        RawInputHandler inputHandler = RawInput.getInputHandler();
        if (inputHandler.isRunning()) {
            inputHandler.setLegacy(true);
        }
    }

    @Inject(method = "unlockCursor", at = @At("HEAD"))
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
                this.client.execute(() -> this.onCursorPos(windowx, x, y));
            }
        });

        GLFW.glfwSetMouseButtonCallback(window, (windowx, button, action, modifiers) -> {
            if (this.shouldProcessGlfw()) {
                this.client.execute(() -> this.onMouseButton(windowx, new net.minecraft.client.input.MouseInput(button, modifiers), action));
            }
        });

        GLFW.glfwSetScrollCallback(window, (windowx, offsetX, offsetY) -> {
            if (this.shouldProcessGlfw()) {
                this.client.execute(() -> this.onMouseScroll(windowx, offsetX, offsetY));
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
                this.client.execute(() -> this.onFilesDropped(windowx, list, finalI));
            }
        });
    }

    @Unique
    private boolean shouldProcessGlfw() {
        return this.client.currentScreen != null;
    }
}