package orbis.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import orbis.compat.OrbisPlatform;

/**
 * Minecraft's Util.getPlatform() (intermediary class_156.method_668, unchanged from 1.14 to 1.20.4)
 * reports UNKNOWN for os.name "Orbis". Mods then take paths that crash: Sodium 0.5 asks OSHI for the
 * graphics cards, and OSHI rejects the platform. Orbis answers as Linux, as LWJGL's platform shim does.
 */
@Mixin(targets = "net.minecraft.class_156", remap = false)
public abstract class UtilMixin {
    @Inject(method = "method_668", at = @At("HEAD"), cancellable = true, remap = false)
    private static void orbis$reportLinux(CallbackInfoReturnable<Object> info) {
        Object linux = OrbisPlatform.linux();
        if (linux != null) info.setReturnValue(linux);
    }
}
