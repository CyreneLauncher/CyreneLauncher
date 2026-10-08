package orbis.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import orbis.compat.TextEntry;

/** ClientTickMixin for 26.1+, which ships with Mojang's names: Minecraft.tick. */
@Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
public abstract class ClientTickMixin26 {
    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void orbis$systemKeyboard(CallbackInfo info) {
        TextEntry.tick(this);
    }
}
