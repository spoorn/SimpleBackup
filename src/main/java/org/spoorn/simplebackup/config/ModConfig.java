package org.spoorn.simplebackup.config;

import draylar.omegaconfig.OmegaConfig;
import draylar.omegaconfig.api.Comment;
import draylar.omegaconfig.api.Config;
import org.spoorn.simplebackup.SimpleBackup;

public class ModConfig implements Config {

    private static ModConfig CONFIG;

    @Comment("Delay in seconds between backups [default = 3600]")
    public int backupIntervalInSeconds = 3600;

    public static void init() {
        CONFIG = OmegaConfig.register(ModConfig.class);
    }

    public static ModConfig get() {
        return CONFIG;
    }

    @Override
    public String getName() {
        return SimpleBackup.MODID;
    }

    @Override
    public String getExtension() {
        // For nicer comments parsing in text editors
        return "json5";
    }
}
