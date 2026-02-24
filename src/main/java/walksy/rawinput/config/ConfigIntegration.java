package walksy.rawinput.config;

import main.walksy.lib.api.WalksyLibApi;
import main.walksy.lib.core.config.impl.LocalConfig;

public class ConfigIntegration implements WalksyLibApi {

    @Override
    public LocalConfig getConfig() {
        Config config = new Config();
        return config.getOrCreateConfig();
    }
}
