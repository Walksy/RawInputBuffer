package walksy.rawinput;

/**
 * Encapsulates raw mouse data
 * <p>
 * Makes it easier to develop for Minecraft versions where {@link net.minecraft.client.input.MouseButtonInfo}
 * is not present
 *
 * @param button the mouse button
 * @param modifiers a bitmask of active keyboard modifiers
 */
public record MouseInput(int button, int modifiers) { }