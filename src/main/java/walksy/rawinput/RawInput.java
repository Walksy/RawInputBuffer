package walksy.rawinput;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.client.event.GuiOpenEvent;

@Mod(modid = "rawinputbuffer", name = "Raw Input Buffer", version = "1.1.0+1.8.9",
    acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true,
    acceptableRemoteVersions = "*")
public final class RawInput {
    private static final Logger LOG = Logger.getLogger("RawInputBuffer");
    private RawInputHandler handler;
    private LwjglMouseBridge bridge;
    private boolean enabled;

    @Mod.EventHandler public void initialize(FMLInitializationEvent event) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            LOG.info("Raw Input Buffer is Windows-only; using the standard mouse");
            return;
        }
        try {
            bridge = new LwjglMouseBridge();
            handler = new RawInputHandler(bridge.windowHandle());
            if (!handler.start()) throw new IllegalStateException("Native input initialization failed");
            enabled = true;
            FMLCommonHandler.instance().bus().register(this);
            MinecraftForge.EVENT_BUS.register(this);
            Runtime.getRuntime().addShutdownHook(new Thread(handler::close, "RawInputBuffer-Shutdown"));
            LOG.info("Raw Input Buffer ready (Forge 1.8.9 / LWJGL 2)");
        } catch (Throwable error) { disable(error); }
    }

    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) update(false);
    }

    @SubscribeEvent public void renderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) update(true);
    }

    @SubscribeEvent public void guiOpen(GuiOpenEvent event) {
        if (!enabled) return;
        try { bridge.release(handler); }
        catch (Throwable error) { disable(error); }
    }

    private void update(boolean frame) {
        if (!enabled) return;
        try { bridge.update(handler, frame); }
        catch (Throwable error) { disable(error); }
    }

    private void disable(Throwable error) {
        enabled = false;
        if (handler != null) {
            handler.close();
            if (bridge != null) {
                try { bridge.release(handler); }
                catch (Throwable ignored) { /* native input is already unregistered */ }
            }
        }
        LOG.log(Level.WARNING, "Raw Input Buffer disabled; using the standard mouse", error);
    }
}
