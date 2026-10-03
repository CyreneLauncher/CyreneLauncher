package orbis.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import runner.lwjgl.LwjglProfile;
import runner.lwjgl.LwjglProfileRegistry;
import runner.lwjgl.MetadataPlatform;
import runner.lwjgl.ResolvedLaunchPlan;
import runner.lwjgl.VersionMetadataResolver;

/** Host adapter around the device runner's exact metadata and LWJGL profile logic. */
public final class HostMetadataPlan {
    private HostMetadataPlan() {}

    private static String json(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            if (ch == '\\' || ch == '"') out.append('\\').append(ch);
            else if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
            else out.append(ch);
        }
        return out.append('"').toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException(
                "usage: HostMetadataPlan VERSION_JSON LIBRARIES_ROOT OUTPUT_JSON");
        Path libraries = Path.of(args[1]).toRealPath();
        ResolvedLaunchPlan plan = VersionMetadataResolver.resolve(
                Path.of(args[0]), libraries, MetadataPlatform.ORBIS);
        LwjglProfile profile = LwjglProfileRegistry.select(plan);
        List<String> entries = new ArrayList<>();
        for (Path file : plan.classpath()) {
            Path relative = libraries.relativize(file);
            if (relative.isAbsolute() || relative.startsWith(".."))
                throw new IllegalArgumentException("classpath path escapes libraries");
            entries.add(json(relative.toString().replace('\\', '/')));
        }
        String output = "{\n  \"version_id\": " + json(plan.versionId())
                + ",\n  \"main_class\": " + json(plan.mainClass())
                + ",\n  \"profile\": " + json(profile.id())
                + ",\n  \"classpath\": [" + String.join(", ", entries)
                + "],\n  \"skipped_natives\": " + plan.skippedNatives().size() + "\n}\n";
        Files.writeString(Path.of(args[2]), output);
    }
}
