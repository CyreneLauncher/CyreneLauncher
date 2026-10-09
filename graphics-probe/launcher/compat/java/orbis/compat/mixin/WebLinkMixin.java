package orbis.compat.mixin;

import java.net.URI;
import java.net.URL;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import orbis.compat.WebLinks;

/**
 * Util.OS.openUri(URI) (intermediary class_156$class_158.method_673, 1.14 to 1.21.11) and, up to 1.20.4,
 * openUrl(URL) (method_669): web links open in the console's web browser (WebLinks).
 */
@Mixin(targets = "net.minecraft.class_156$class_158", remap = false)
public abstract class WebLinkMixin {
    @Inject(method = "method_673(Ljava/net/URI;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void orbis$openUri(URI uri, CallbackInfo info) {
        if (uri != null && WebLinks.open(this, uri.toString())) info.cancel();
    }

    @Inject(method = "method_669(Ljava/net/URL;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void orbis$openUrl(URL url, CallbackInfo info) {
        if (url != null && WebLinks.open(this, url.toString())) info.cancel();
    }
}
