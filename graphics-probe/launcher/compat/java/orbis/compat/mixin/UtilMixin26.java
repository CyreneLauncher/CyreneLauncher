package orbis.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import orbis.compat.OrbisPlatform;

/** UtilMixin for 26.1+, which ships with Mojang's names: Util.getPlatform. */
@Mixin(targets = "net.minecraft.util.Util", remap = false)
public abstract class UtilMixin26 {
    @Inject(method = "getPlatform", at = @At("HEAD"), cancellable = true, remap = false)
    private static void orbis$reportLinux(CallbackInfoReturnable<Object> info) {
        Object linux = OrbisPlatform.linux();
        if (linux != null) info.setReturnValue(linux);
    }
}
