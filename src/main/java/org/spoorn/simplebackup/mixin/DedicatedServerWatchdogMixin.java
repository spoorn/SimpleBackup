package org.spoorn.simplebackup.mixin;

import net.minecraft.server.dedicated.DedicatedServerWatchdog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spoorn.simplebackup.SimpleBackup;
import org.spoorn.simplebackup.SimpleBackupTask;

@Mixin(DedicatedServerWatchdog.class)
public class DedicatedServerWatchdogMixin {
    
    private static Logger log = LogManager.getLogger("DedicatedServerWatchdogMixin");

    @Shadow @Final private long maxTickTime;

    /**
     * If we are doing a server ended backup, it may take longer than the max-tick-time set in server.properties.
     * Bypass the watchdog crash if we are in the middle of a backup.
     */
    @ModifyVariable(method = "run", at = @At(value = "STORE"), ordinal = 2)
    private long bypassWatchdogForServerEndBackup(long n) {
        if (n > this.maxTickTime) {
            SimpleBackupTask serverEndBackupTask = SimpleBackup.serverEndBackupTask.get();
            if (serverEndBackupTask != null && serverEndBackupTask.isProcessing) {
                log.info("SimpleBackup server end backup task is still ongoing past max-tick-time.  Waiting for it to finish before stopping server...");
                return -1;  // Don't alert watchdog
            }
        }
        
        return n;
    }
}
