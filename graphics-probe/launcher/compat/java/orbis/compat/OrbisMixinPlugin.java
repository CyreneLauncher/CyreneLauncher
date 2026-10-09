package orbis.compat;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Picks the mixins for the Minecraft being run. Up to 1.21.11 Fabric runs Minecraft by its intermediary
 * names (class_310); 26.1 and later ship unobfuscated, with Mojang's names (net.minecraft.client.Minecraft).
 * Only the matching set is listed, so neither logs a missing target.
 */
public final class OrbisMixinPlugin implements IMixinConfigPlugin {
    private boolean mojangNames;

    @Override public void onLoad(String mixinPackage) {
        mojangNames = present("net/minecraft/client/Minecraft.class") && !present("net/minecraft/class_310.class");
    }

    private boolean present(String resource) {
        ClassLoader own = getClass().getClassLoader();
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return (own != null && own.getResource(resource) != null) || (context != null && context.getResource(resource) != null);
    }

    @Override public List<String> getMixins() {
        List<String> mixins = new ArrayList<>(mojangNames
                ? Arrays.asList("ClientTickMixin26", "UtilMixin26", "WebLinkMixin26")
                : Arrays.asList("ClientTickMixin", "UtilMixin", "WebLinkMixin"));
        if (present("net/minecraft/client/gui/components/debug/DebugEntrySystemSpecs.class")) {
            mixins.add("CpuInfoMixin");
        }
        return mixins;
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
