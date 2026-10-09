package orbis.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import orbis.compat.GamepadFeed;

/**
 * Controlify 3.5+ (Minecraft 26.1+) reads controllers only through SDL3: right before its controller
 * manager polls SDL, the console's controller is handed to the console's libSDL3 (GamepadFeed), which
 * presents it as a virtual PS4 gamepad. Without Controlify nothing is applied.
 */
@Pseudo
@Mixin(targets = "dev.isxander.controlify.controllermanager.SDLControllerManager", remap = false)
public abstract class ControlifySdlFeedMixin {
    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void orbis$feedController(boolean outOfFocus, CallbackInfo info) {
        GamepadFeed.tick(this);
    }
}
