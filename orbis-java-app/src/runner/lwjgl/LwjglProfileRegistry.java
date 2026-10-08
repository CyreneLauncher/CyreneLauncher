package runner.lwjgl;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static runner.lwjgl.LaunchPlanException.Category.PROFILE_UNSUPPORTED;

/**
 * Explicit registry of tested LWJGL profiles. Parsing metadata successfully never implies
 * support: selection requires the complete module set and the audited artifacts.
 */
public final class LwjglProfileRegistry {
    /** Minecraft 1.18.2 Linux/Windows track; see graphics-probe/results/lwjgl-abi-audit.md. */
    public static final LwjglProfile MINECRAFT_1_18_2 = new LwjglProfile(
            "minecraft-1.18.2-lwjgl-3.2.2-orbis-v1",
            Map.of("org.lwjgl:lwjgl", "3.2.2",
                    "org.lwjgl:lwjgl-glfw", "3.2.2",
                    "org.lwjgl:lwjgl-jemalloc", "3.2.2",
                    "org.lwjgl:lwjgl-openal", "3.2.2",
                    "org.lwjgl:lwjgl-opengl", "3.2.2",
                    "org.lwjgl:lwjgl-stb", "3.2.2",
                    "org.lwjgl:lwjgl-tinyfd", "3.2.2"),
            Map.of("org.lwjgl:lwjgl", "8ad6294407e15780b43e84929c40e4c5e997972e",
                    "org.lwjgl:lwjgl-glfw", "d3ad4df38e400b8afba1de63f84338809399df5b",
                    "org.lwjgl:lwjgl-jemalloc", "ee8e57a79300f78294576d87c4a587f8c99402e2",
                    "org.lwjgl:lwjgl-openal", "2b772a102b0a11ee5f2109a5b136f4dc7c630827",
                    "org.lwjgl:lwjgl-opengl", "6ac5bb88b44c43ea195a570aab059f63da004cd8",
                    "org.lwjgl:lwjgl-stb", "3b8e6ebc5851dd3d17e37e5cadce2eff2a429f0f",
                    "org.lwjgl:lwjgl-tinyfd", "fcbe606c8f8da6f8f9a05e2c540eb1ee8632b0e9"));

    /**
     * Minecraft 1.19 to 1.21.11: the LWJGL 3.3.3 set 1.20.5 to 1.21.11 declare (Mojang version metadata),
     * which adds lwjgl-freetype; the launcher gives it to 1.19 to 1.20.4 too (they declare 3.3.1 or 3.3.2).
     * Runs on LWJGL 3.3.3 natives built for Orbis with libffi.
     */
    public static final LwjglProfile MINECRAFT_1_20_5 = new LwjglProfile(
            "minecraft-1.20.5-lwjgl-3.3.3-orbis-v1",
            Map.of("org.lwjgl:lwjgl", "3.3.3",
                    "org.lwjgl:lwjgl-freetype", "3.3.3",
                    "org.lwjgl:lwjgl-glfw", "3.3.3",
                    "org.lwjgl:lwjgl-jemalloc", "3.3.3",
                    "org.lwjgl:lwjgl-openal", "3.3.3",
                    "org.lwjgl:lwjgl-opengl", "3.3.3",
                    "org.lwjgl:lwjgl-stb", "3.3.3",
                    "org.lwjgl:lwjgl-tinyfd", "3.3.3"),
            Map.of("org.lwjgl:lwjgl", "29589b5f87ed335a6c7e7ee6a5775f81f97ecb84",
                    "org.lwjgl:lwjgl-freetype", "a0db6c84a8becc8ca05f9dbfa985edc348a824c7",
                    "org.lwjgl:lwjgl-glfw", "efa1eb78c5ccd840e9f329717109b5e892d72f8e",
                    "org.lwjgl:lwjgl-jemalloc", "b543467b7ff3c6920539a88ee602d34098628be5",
                    "org.lwjgl:lwjgl-openal", "daada81ceb5fc0c291fbfdd4433cb8d9423577f2",
                    "org.lwjgl:lwjgl-opengl", "02f6b0147078396a58979125a4c947664e98293a",
                    "org.lwjgl:lwjgl-stb", "25dd6161988d7e65f71d5065c99902402ee32746",
                    "org.lwjgl:lwjgl-tinyfd", "82d755ca94b102e9ca77283b9e2dc46d1b15fbe5"));

    /**
     * Minecraft 26.1 and 26.2: the LWJGL 3.4.1 set they declare (Mojang version metadata). Runs on LWJGL
     * 3.4.1 natives built for Orbis with libffi 3.5.0; on Java 25 LWJGL calls functions and callbacks
     * through the JDK's foreign function linker.
     */
    public static final LwjglProfile MINECRAFT_26_1 = new LwjglProfile(
            "minecraft-26.1-lwjgl-3.4.1-orbis-v1",
            Map.of("org.lwjgl:lwjgl", "3.4.1",
                    "org.lwjgl:lwjgl-freetype", "3.4.1",
                    "org.lwjgl:lwjgl-glfw", "3.4.1",
                    "org.lwjgl:lwjgl-jemalloc", "3.4.1",
                    "org.lwjgl:lwjgl-openal", "3.4.1",
                    "org.lwjgl:lwjgl-opengl", "3.4.1",
                    "org.lwjgl:lwjgl-stb", "3.4.1",
                    "org.lwjgl:lwjgl-tinyfd", "3.4.1"),
            Map.of("org.lwjgl:lwjgl", "6105690714874b995aa7812e4f7732a21109276c",
                    "org.lwjgl:lwjgl-freetype", "caa75a37e857efc9b0686e434d708d6420c5b6ea",
                    "org.lwjgl:lwjgl-glfw", "a782b1ddd175c9107bf300fd92920579ea65e2a4",
                    "org.lwjgl:lwjgl-jemalloc", "16b663092854fc6adfe0a941e61cae2bb2e437b6",
                    "org.lwjgl:lwjgl-openal", "8508bd60589de0539aa465b78fcf838efd07ae9a",
                    "org.lwjgl:lwjgl-opengl", "830412cab823c029cda6b9729f9d2ed36cf79959",
                    "org.lwjgl:lwjgl-stb", "f077c3dafc31924fbe8acb0b913f08977ba27f8e",
                    "org.lwjgl:lwjgl-tinyfd", "562697d40dbd2ed0424bf24dcf65d51170f81adf"));

    private static final List<LwjglProfile> PROFILES = List.of(MINECRAFT_1_18_2, MINECRAFT_1_20_5, MINECRAFT_26_1);

    private LwjglProfileRegistry() {}

    public static List<LwjglProfile> profiles() {
        return PROFILES;
    }

    /** Selects the profile whose exact module set and Java artifacts match the plan. */
    public static LwjglProfile select(ResolvedLaunchPlan plan) throws LaunchPlanException {
        return select(plan, PROFILES);
    }

    /** Selects a profile and validates the graphics bundle that provides it. */
    public static GraphicsBundle select(ResolvedLaunchPlan plan, Path bundleRoot) throws LaunchPlanException {
        return GraphicsBundle.load(bundleRoot, select(plan));
    }

    static LwjglProfile select(ResolvedLaunchPlan plan, List<LwjglProfile> profiles)
            throws LaunchPlanException {
        Map<String, String> requested = plan.lwjglModules();
        if (requested.isEmpty())
            throw new LaunchPlanException(PROFILE_UNSUPPORTED, plan.versionId() + " declares no LWJGL modules");
        for (LwjglProfile profile : profiles) {
            if (!profile.modules().equals(requested)) continue;
            for (Map.Entry<String, String> pin : profile.javaSha1().entrySet()) {
                String actual = plan.lwjglSha1().get(pin.getKey());
                if (!pin.getValue().equals(actual))
                    throw new LaunchPlanException(PROFILE_UNSUPPORTED, pin.getKey() + " artifact SHA-1 "
                            + actual + " is not the audited " + pin.getValue() + " for " + profile.id());
            }
            return profile;
        }
        throw new LaunchPlanException(PROFILE_UNSUPPORTED, "no tested profile for LWJGL set " + requested
                + " (supported: " + describe(profiles) + ")");
    }

    private static String describe(List<LwjglProfile> profiles) {
        StringBuilder builder = new StringBuilder();
        for (LwjglProfile profile : profiles) {
            if (builder.length() > 0) builder.append("; ");
            builder.append(profile.id()).append(' ').append(profile.modules());
        }
        return builder.toString();
    }
}
