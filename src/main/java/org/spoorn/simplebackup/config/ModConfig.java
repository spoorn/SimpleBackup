package org.spoorn.simplebackup.config;

import draylar.omegaconfig.OmegaConfig;
import draylar.omegaconfig.api.Comment;
import draylar.omegaconfig.api.Config;
import org.spoorn.simplebackup.SimpleBackup;

public class ModConfig implements Config {

    private static ModConfig CONFIG;

    @Comment("True to enable automatic backups in intervals.  False to disable [default = true]")
    public boolean enableAutomaticBackups = true;
    
    @Comment("Delay in seconds between automatic backups. [default = 3600] [minimum = 60]")
    public int backupIntervalInSeconds = 3600;
    
    @Comment("True to trigger a backup when server is stopped.  False to disable [default = true]")
    public boolean enableServerStoppedBackup = true;

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
