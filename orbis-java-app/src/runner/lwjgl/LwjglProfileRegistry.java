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

    private static final List<LwjglProfile> PROFILES = List.of(MINECRAFT_1_18_2);

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
