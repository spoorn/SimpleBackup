package org.spoorn.simplebackup.config;

import draylar.omegaconfig.OmegaConfig;
import draylar.omegaconfig.api.Comment;
import draylar.omegaconfig.api.Config;
import org.spoorn.simplebackup.SimpleBackup;
import org.spoorn.simplebackup.util.SimpleBackupUtil;

import java.util.HashMap;
import java.util.Map;

public class ModConfig implements Config {

    private static ModConfig CONFIG;

    @Comment("True to enable automatic backups in intervals.  False to disable [default = true]")
    public boolean enableAutomaticBackups = true;
    
    @Comment("Delay in seconds between automatic backups. [default = 3600] [minimum = 60]")
    public int backupIntervalInSeconds = 3600;
    
    @Comment("Only backup if players were online for the backup interval. [default = true]\n" +
            "You might want to set this to false if the server is loading chunks even when no one is online.")
    public boolean onlyBackupIfPlayersOnline = true;
    
    @Comment("True to trigger a backup when server is stopped.  False to disable [default = true]")
    public boolean enableServerStoppedBackup = true;
    
    @Comment("Backup format.  Supports simply backing up as a direct copy of the folder, or ZIP [default = \"ZIP\"]\n" +
            "Supported formats: \"DIRECTORY\", \"ZIP\"")
    public String backupFormat = "ZIP";
    
    @Comment("Broadcast messages when server is backing up and success/failed.  These are in the config file to allow\n" +
            "servers to use whatever language they want without updating the mod source directly.  If you remove these,\n" +
            "it will default to english.")
    public Map<String, String> broadcastMessages = new HashMap<>(Map.of(
            "simplebackup.backup.broadcast", "Starting server backup...",
            "simplebackup.backup.success.broadcast", "Server was successfully backed up to ",
            "simplebackup.backup.failed.broadcast1", "Server failed to backup to ",
            "simplebackup.backup.failed.broadcast2", ".  Please check the server logs for errors!"
    ));

    public static void init() {
        CONFIG = OmegaConfig.register(ModConfig.class);
        if (!SimpleBackupUtil.ZIP_FORMAT.equals(CONFIG.backupFormat) && !SimpleBackupUtil.DIRECTORY_FORMAT.equals(CONFIG.backupFormat)) {
            throw new IllegalArgumentException("SimpleBackup config 'backupFormat' is invalid!");
        }
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
