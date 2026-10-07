package orbis.compat.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Controlify 2 asks for its SDL natives once a controller connects, and loads them through JNA, which has
 * no native part for Orbis: JNA's class fails to initialise and the NoClassDefFoundError crashes the game.
 * Answering "no natives", as when the player declines them, keeps Controlify on GLFW's gamepad API, which
 * the Orbis GLFW facade implements for the console's controller. Without Controlify nothing is applied.
 */
@Pseudo
@Mixin(targets = "dev.isxander.controlify.Controlify", remap = false)
public abstract class ControlifyNativesMixin {
    @Inject(method = "askNatives", at = @At("HEAD"), cancellable = true, remap = false)
    private void orbis$noNatives(CallbackInfoReturnable<CompletableFuture<Boolean>> info) {
        info.setReturnValue(CompletableFuture.completedFuture(Boolean.FALSE));
    }
}
