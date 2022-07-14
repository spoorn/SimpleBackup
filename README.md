# Description
Simple Backup utility for servers.  Automatic backups at intervals, with configurable options such as compression to zip file, pausing auto backups if no players are online, etc.

# Features
- Automatic backups at configurable intervals
- Pausing backups if no players are online to save resources
- Backups are done asynchronously to minimize impact on game performance during backups
- Compressing backup to .zip or .tar.lz4 file
- Configurations to limit number of backups to keep, and guards to ensure backups do not exceed the disk space
- Manual backups can be triggered with command `/simplebackup start`, `/simplebackup zip`, `/simplebackup lz4`, `/simplebackup directory`
- Manual backup permissions can be configured in the config
- Backup is stored in the game directory under backup/ parallel to mods/ and config/ folders, or can be configured to any absolute path in the system
- Backup file format is YYYY-MM-DD_HH-MM-SS (example: backup/2022-05-04_05-04-13
- Message will be broadcast to players when backup is in progress.  The message is configured in the config to allow for language translations and keep the mod completely server-side.  The messages can be disabled
- And yeah, the mod is only required on the server!

# Config
You can tune all the features in the config file at `config/simplebackup.json5`. Check out the config documentation at https://github.com/spoorn/SimpleBackup/blob/main/config-documentation.json5 for more details

# Backup Formats

| Format | Description | How to extract |
| :---: | ----------- | --- |
| ZIP | Standard .zip deflate compression (level 5).  Slow to compress, but universal and recognized by virtually all systems.<br /><br />__Recommend to use this option by default unless backups take a long time.__ | All systems should have their own way to extract .zip files |
| LZ4 | Extremely fast compression, many many times faster than ZIP.  File size will be a bit larger, but this doesn't matter too much for Minecraft worlds as most of the files can't be further compressed anyways.  Using this format will actually archive the file as a .tar first, then compress it to a .tar.lz4.  See https://github.com/lz4/lz4-java for more info on lz4.<br /><br />__Recommend to use this format if backups are taking a long time with ZIP due to the world folder being very large (10+ GB) or slow processing.__ | You'll need to extract it twice.  You can use [7-Zip-zstd](https://github.com/mcmilk/7-Zip-zstd) (_Click on Releases_) to first extract from the .lz4 to get a tar archive, and then extract the .tar |
| DIRECTORY | Simply copies the world folder to the backup folder | N/A |

## Dependencies
This mod requires:
- Fabric API - https://www.curseforge.com/minecraft/mc-mods/fabric-api 
 
If you like what you see, check out my other mods! :  https://www.curseforge.com/members/spoorn/projects
