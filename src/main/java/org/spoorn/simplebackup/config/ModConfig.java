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
    private static final Map<String, String> DEFAULT_BROADCAST_MESSAGES = Map.of(
            "simplebackup.backup.broadcast", "Starting server backup...",
            "simplebackup.backup.success.broadcast", "Server was successfully backed up to ",
            "simplebackup.backup.failed.broadcast1", "Server failed to backup to ",
            "simplebackup.backup.failed.broadcast2", ".  Please check the server logs for errors!",
            "simplebackup.manualbackup.alreadyexists", "There is already an ongoing manual backup.  Please wait for it to finish before starting another!",
            "simplebackup.manualbackup.started", " triggered a manual backup",
            "simplebackup.manualbackup.disabled", "Manual backups are disabled by the server!",
            "simplebackup.manualbackup.notallowed", "You don't have permissions to trigger a manual backup!  Sorry :("
    );

    @Comment("True to enable automatic backups in intervals.  False to disable. [default = true]\n" +
            "In single player, only backs up if the game is not paused.")
    public boolean enableAutomaticBackups = true;
    
    @Comment("Delay in seconds between automatic backups. [default = 3600] [minimum = 10]")
    public int backupIntervalInSeconds = 3600;
    
    @Comment("Only backup if players were online for the backup interval. [default = true]\n" +
            "You might want to set this to false if the server is loading chunks even when no one is online.")
    public boolean onlyBackupIfPlayersOnline = true;
    
    @Comment("True to trigger a backup when server is stopped.  False to disable [default = true]\n" +
            "If backup takes longer than max-tick-time set in server.properties, the server thread will wait until backup finishes.\n" +
            "Note: this may not properly finish the backup if you try to terminate the process with an interruption\n" +
            "\tsuch as CTRL+C!  Double check the backup file is the size you expect")
    public boolean enableServerStoppedBackup = true;
    
    @Comment("Backup format.  Supports simply backing up as a direct copy of the folder, or ZIP [default = \"ZIP\"]\n" +
            "Supported formats: \"DIRECTORY\", \"ZIP\", \"LZ4\"\n" +
            "\tDIRECTORY: copies the world folder as-is\n" +
            "\tZIP: copies the world folder and zips it into a .zip file\n" +
            "\tLZ4: archives the world folder into a .tar, then compresses using lz4, making a .tar.lz4 file\n" +
            "LZ4 can be extracted/decompressed using 7-Zip-zstd: https://github.com/mcmilk/7-Zip-zstd\n" +
            "See https://github.com/spoorn/SimpleBackup/blob/main/README.md for more information on the backup formats")
    public String backupFormat = "ZIP";
    
    @Comment("Number of threads to execute backup.  Allows for parallel compression.  Only applies for LZ4 format currently!  [default = 1]\n" +
            "Recommend only increasing this value if your world is very large and backups take a while.\n" +
            "Recommend setting numThreads for multi-threaded processing to the number of cores in your CPU, or a multiple of that number.\n" +
            "This is capped to number of processors * 4 for your safety!")
    public int numThreads = 1;
    
    @Comment("Buffer size in bytes for multi-threading (when numThreads > 1) used for compressing and merging files [default = 8192]")
    public int multiThreadBufferSize = 8192;
    
    @Comment("Percentage of disk space available required before creating a backup.  [default = 20]\n" +
            "This will prevent generating backups if your disk space is getting close to maxing out.")
    public int percentageAvailableDiskSpaceRequirement = 20;
    
    @Comment("Backup folder.  If this is set to a relative path, it will be relative to the game directory [default = \"backup\"]\n" +
            "This can be an absolute path as well if you want to store backups in a different location.\n" +
            "Note: If you are using backslashes '\\' instead of forward slash, you will need to escape it with double backslash '\\\\'.\n" +
            "Examples:\n" +
            "\t\"backupPath\": \"F:/mybackups/backup\",\n" +
            "\t\"backupPath\": \"F:\\\\mybackups\\\\backup\",")
    public String backupPath = "backup";
    
    @Comment("Maximum number of backups to keep at a given time.  [default = 10]\n" +
            "If we generate a backup, but have more backups than this number, the oldest backup will be deleted.")
    public int maxBackupsToKeep = 10;
    
    @Comment("True to enable manual backups, false to disable  [default = true]")
    public boolean enableManualBackups = true;
    
    @Comment("Permission level to allow manual backups.  [4 = Ops] [0 = everyone] [default = 4]")
    public int permissionLevelForManualBackups = 4;
    
    @Comment("True to broadcast across the server when backups are triggered and finished.  False to disable. [default = true]")
    public boolean broadcastBackupMessage = true;
    
    @Comment("Percentage (%) interval to log to server console the backup progress.  [default = 10]\n" +
            "Set this to a value <= 0 or > 100 to not print anything.")
    public int intervalPercentageToLogBackupProgress = 10;
    
    @Comment("Broadcast messages when server is backing up and success/failed.  These are in the config file to allow\n" +
            "servers to use whatever language they want without updating the mod source directly.  Default language is english")
    public Map<String, String> broadcastMessages = new HashMap<>(DEFAULT_BROADCAST_MESSAGES);

    public static void init() {
        CONFIG = OmegaConfig.register(ModConfig.class);
        if (!SimpleBackupUtil.ZIP_FORMAT.equals(CONFIG.backupFormat) && !SimpleBackupUtil.DIRECTORY_FORMAT.equals(CONFIG.backupFormat)
            && !SimpleBackupUtil.LZ4_FORMAT.equals(CONFIG.backupFormat)) {
            throw new IllegalArgumentException("SimpleBackup config 'backupFormat' is invalid!");
        }
        CONFIG.numThreads = Math.min(Runtime.getRuntime().availableProcessors() * 4, CONFIG.numThreads);
    }

    public static ModConfig get() {
        return CONFIG;
    }

    @Override
    public void save() {
        for (Map.Entry<String, String> entry : DEFAULT_BROADCAST_MESSAGES.entrySet()) {
            if (!this.broadcastMessages.containsKey(entry.getKey())) {
                this.broadcastMessages.put(entry.getKey(), entry.getValue());
            }
        }
        Config.super.save();
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
