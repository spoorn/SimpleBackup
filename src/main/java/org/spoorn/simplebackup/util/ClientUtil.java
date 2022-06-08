package org.spoorn.simplebackup.util;

import net.minecraft.client.MinecraftClient;

/**
 * Class wrapper indirection for Client environment locked code to not crash server side.
 */
public class ClientUtil {
    
    public static boolean isPaused() {
        return MinecraftClient.getInstance().isPaused();
    }
}
