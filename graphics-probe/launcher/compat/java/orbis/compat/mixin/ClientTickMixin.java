package orbis.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import orbis.compat.TextEntry;

/**
 * MinecraftClient.tick (intermediary class_310.method_1574, the same from 1.14 to 1.20.4) drives the
 * system keyboard for focused text fields and signs (TextEntry).
 */
@Mixin(targets = "net.minecraft.class_310", remap = false)
public abstract class ClientTickMixin {
    @Inject(method = "method_1574", at = @At("HEAD"), remap = false)
    private void orbis$systemKeyboard(CallbackInfo info) {
        TextEntry.tick(this);
    }
}
