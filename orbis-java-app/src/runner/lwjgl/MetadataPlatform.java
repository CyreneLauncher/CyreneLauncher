package runner.lwjgl;

/**
 * Values that Mojang library rules are evaluated against. This is an explicit target
 * mapping: it selects which Java artifact track applies and never implies that the
 * platform's upstream native classifiers can run.
 */
public record MetadataPlatform(String osName, String osArch) {
    /** Orbis follows Mojang's Linux Java artifact track; natives come from the graphics bundle. */
    public static final MetadataPlatform ORBIS = new MetadataPlatform("linux", "x86_64");

    public MetadataPlatform {
        if (osName == null || osName.isEmpty() || osArch == null || osArch.isEmpty())
            throw new IllegalArgumentException("platform os name and arch are required");
    }
}
