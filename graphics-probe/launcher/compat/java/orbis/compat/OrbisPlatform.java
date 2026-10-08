package orbis.compat;

/**
 * The LINUX constant of Minecraft's Util.OS (intermediary class_156$class_158.field_1135; Util$OS.LINUX from
 * 26.1, which ships with Mojang's names), on Orbis only.
 */
public final class OrbisPlatform {
    private static volatile boolean resolved;
    private static volatile Object linux;

    private OrbisPlatform() { }

    public static Object linux() {
        if (!System.getProperty("os.name", "").startsWith("Orbis")) return null;
        if (!resolved) {
            linux = constant("net.minecraft.class_156$class_158", "field_1135");
            if (linux == null) linux = constant("net.minecraft.util.Util$OS", "LINUX");   // 26.1+: Mojang's names
            resolved = true;
        }
        return linux;
    }

    private static Object constant(String className, String field) {
        try {
            return Class.forName(className, false, OrbisPlatform.class.getClassLoader()).getField(field).get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }
}
