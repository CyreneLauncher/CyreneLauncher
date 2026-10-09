package orbis.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** OSHI cannot query the CPU model on Orbis; retain any successfully detected name. */
@Mixin(targets = "net.minecraft.client.gui.components.debug.DebugEntrySystemSpecs", remap = false)
public abstract class CpuInfoMixin {
    @Inject(method = "getCpuInfo()Ljava/lang/String;", at = @At("RETURN"), cancellable = true, remap = false)
    private static void orbis$cpuName(CallbackInfoReturnable<String> info) {
        if (!System.getProperty("os.name", "").startsWith("Orbis")) return;
        String detected = info.getReturnValue();
        if (detected == null || detected.trim().isEmpty() || "<unknown>".equals(detected.trim())) {
            info.setReturnValue("AMD Jaguar (PS4)");
        }
    }
}
