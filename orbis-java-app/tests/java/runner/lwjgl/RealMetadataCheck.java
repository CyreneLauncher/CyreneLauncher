package runner.lwjgl;

import java.nio.file.Path;

import static runner.lwjgl.TestSupport.equal;

/** Resolves prepared official metadata; arguments: version JSON, libraries root. */
public final class RealMetadataCheck {
    public static void main(String[] args) throws Exception {
        ResolvedLaunchPlan plan = VersionMetadataResolver.resolve(Path.of(args[0]), Path.of(args[1]),
                MetadataPlatform.ORBIS);
        equal(LwjglProfileRegistry.MINECRAFT_1_18_2, LwjglProfileRegistry.select(plan), "official 1.18.2 profile");
        equal("net.minecraft.client.main.Main", plan.mainClass(), "official main class");
        System.out.println("official metadata: " + plan.classpath().size() + " classpath JARs, "
                + plan.skippedNatives().size() + " native artifacts skipped, profile "
                + LwjglProfileRegistry.MINECRAFT_1_18_2.id());
    }
}
