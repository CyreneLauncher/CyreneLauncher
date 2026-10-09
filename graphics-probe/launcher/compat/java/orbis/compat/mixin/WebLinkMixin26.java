package orbis.compat.mixin;

import java.net.URI;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import orbis.compat.WebLinks;

/** WebLinkMixin for 26.1+, which ships with Mojang's names: Util.OS.openUri(URI). */
@Mixin(targets = "net.minecraft.util.Util$OS", remap = false)
public abstract class WebLinkMixin26 {
    @Inject(method = "openUri(Ljava/net/URI;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void orbis$openUri(URI uri, CallbackInfo info) {
        if (uri != null && WebLinks.open(this, uri.toString())) info.cancel();
    }
}
