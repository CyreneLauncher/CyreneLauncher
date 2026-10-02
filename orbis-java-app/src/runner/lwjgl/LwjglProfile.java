package runner.lwjgl;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * One tested LWJGL compatibility profile: an exact module/version set and the audited
 * SHA-1 of each Java artifact. A profile never matches a superset, subset or mixture.
 *
 * @param id        stable identifier shared with {@code graphics-bundle.json}
 * @param modules   {@code org.lwjgl:<module>} to exact version
 * @param javaSha1  {@code org.lwjgl:<module>} to audited Java artifact SHA-1
 */
public record LwjglProfile(String id, Map<String, String> modules, Map<String, String> javaSha1) {
    public LwjglProfile {
        if (id == null || id.isEmpty() || modules.isEmpty() || !modules.keySet().equals(javaSha1.keySet()))
            throw new IllegalArgumentException("profile needs an id and one SHA-1 per module");
        modules = Collections.unmodifiableMap(new TreeMap<>(modules));
        javaSha1 = Collections.unmodifiableMap(new TreeMap<>(javaSha1));
    }
}
