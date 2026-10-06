package orbis.launcher;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Credential-free, short-lived one-shot intent for the next application process. */
record LaunchRequest(int schemaVersion, String instanceId, String accountId, long createdAt,
                     String preparationId) {
    static final int SCHEMA = 1;
    static final long MAX_AGE_MILLIS = 120_000;

    static LaunchRequest create(String instanceId, String accountId) throws IOException {
        LaunchRequest request = new LaunchRequest(SCHEMA, instanceId, accountId,
                Instant.now().toEpochMilli(), UUID.randomUUID().toString());
        request.validate(false);
        return request;
    }

    String encode() {
        JsonObject json = new JsonObject();
        json.addProperty("schema_version", schemaVersion);
        json.addProperty("instance_id", instanceId);
        json.addProperty("account_id", accountId);
        json.addProperty("created_at", createdAt);
        json.addProperty("preparation_id", preparationId);
        return json.toString() + "\n";
    }

    static LaunchRequest decode(String text) throws IOException {
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            LaunchRequest request = new LaunchRequest(json.get("schema_version").getAsInt(),
                    json.get("instance_id").getAsString(), json.get("account_id").getAsString(),
                    json.get("created_at").getAsLong(), json.get("preparation_id").getAsString());
            request.validate(true);
            return request;
        } catch (RuntimeException invalid) {
            throw new IOException("The pending launch request is invalid");
        }
    }

    void validate(boolean checkAge) throws IOException {
        if (schemaVersion != SCHEMA || !safeId(instanceId) || !safeId(accountId)
                || preparationId == null || !preparationId.matches("[a-fA-F0-9-]{36}"))
            throw new IOException("The pending launch request is invalid");
        try {
            if (!UUID.fromString(preparationId).toString().equals(preparationId))
                throw new IllegalArgumentException("noncanonical ID");
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid preparation ID"); }
        if (checkAge) {
            long now = System.currentTimeMillis();
            if (createdAt < now - MAX_AGE_MILLIS || createdAt > now + 5_000)
                throw new IOException("The pending launch has expired; select Play again");
        }
    }

    private static boolean safeId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }
}
