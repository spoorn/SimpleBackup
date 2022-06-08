package org.spoorn.simplebackup.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spoorn.simplebackup.SimpleBackup;
import org.spoorn.simplebackup.SimpleBackupTask;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Shadow public abstract boolean isPaused();
    
    private static boolean lastPaused = false;

    @Inject(method = "render", at = @At(value = "TAIL"))
    private void unlockBackupWhenUnpause(boolean tick, CallbackInfo ci) {
        if (this.isPaused() != lastPaused) {
            lastPaused = this.isPaused();

            SimpleBackupTask mainTask = SimpleBackup.simpleBackupTask.get();
            if (mainTask != null) {
                synchronized (mainTask.lock) {
                    mainTask.lock.notify();
                }
            }
        }
    }
}
