package runner.lwjgl;

/** Launch-plan failure with a stable category for runner diagnostics. */
public final class LaunchPlanException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Category {
        /** Unreadable, oversized, malformed or non-UTF-8 JSON. */
        METADATA_PARSE,
        /** Well-formed JSON using a field, rule or format this resolver does not support. */
        METADATA_UNSUPPORTED,
        /** The same coordinate declared twice with different artifacts. */
        METADATA_CONFLICT,
        /** Artifact path escapes the libraries root or is not a plain relative path. */
        ARTIFACT_PATH,
        /** Declared artifact absent or not a regular file. */
        ARTIFACT_MISSING,
        /** Artifact size or hash differs from the metadata. */
        ARTIFACT_MISMATCH,
        /** One LWJGL module selected at more than one version. */
        LWJGL_CONFLICT,
        /** No registered profile accepts the resolved LWJGL set. */
        PROFILE_UNSUPPORTED,
        /** Graphics bundle manifest or files missing, malformed or mismatched. */
        BUNDLE_INVALID
    }

    private final Category category;

    public LaunchPlanException(Category category, String detail) {
        super(category + ": " + detail);
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
