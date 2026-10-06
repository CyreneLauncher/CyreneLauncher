package orbis.compat;

/** The LINUX constant of Minecraft's Util.OS (intermediary class_156$class_158.field_1135), on Orbis only. */
public final class OrbisPlatform {
    private static volatile boolean resolved;
    private static volatile Object linux;

    private OrbisPlatform() { }

    public static Object linux() {
        if (!System.getProperty("os.name", "").startsWith("Orbis")) return null;
        if (!resolved) {
            try {
                Class<?> os = Class.forName("net.minecraft.class_156$class_158", false, OrbisPlatform.class.getClassLoader());
                linux = os.getField("field_1135").get(null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                linux = null;
            }
            resolved = true;
        }
        return linux;
    }
}
