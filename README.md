# Description
Simple Backup utility for servers.  Automatic backups at intervals, with configurable options such as compression to zip file, pausing auto backups if no players are online, etc.

# Features
- Automatic backups at configurable intervals
- Pausing backups if no players are online to save resources
- Backups are done asynchronously to minimize impact on game performance during backups
- Compressing backup to .zip file
- Configurations to limit number of backups to keep, and guards to ensure backups do not exceed the disk space
- Manual backups can be triggered with command /simplebackup start
- Manual backup permissions can be configured in the config
- Backup is stored in the game directory under backup/ parallel to mods/ and config/ folders
- Backup file format is YYYY-MM-DD_HH-MM-SS (example: backup/2022-05-04_05-04-13
- Message will be broadcast to players when backup is in progress.  The message is configured in the config to allow for language translations and keep the mod completely server-side
- And yeah, the mod is only required on the server!

# Config
You can tune all the features in the config file at `config/simplebackup.json5`. Check out the config documentation at https://github.com/spoorn/SimpleBackup/blob/main/config-documentation.json5 for more details

## Dependencies
This mod requires:
- Fabric API - https://www.curseforge.com/minecraft/mc-mods/fabric-api 
 
If you like what you see, check out my other mods! :  https://www.curseforge.com/members/spoorn/projects
