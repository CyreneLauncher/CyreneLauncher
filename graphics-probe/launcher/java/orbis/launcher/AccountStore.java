package orbis.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Account metadata is separate from the locally stored Microsoft refresh credentials. */
final class AccountStore {
    static final String OFFLINE_ID = "offline";
    static final String OWNERSHIP_REQUIRED =
            "Sign in once with a Microsoft account that owns Minecraft: Java Edition to install Minecraft.";
    private static final int SCHEMA = 1;
    private static final int MAX_METADATA = 65536;
    private static final int MAX_CREDENTIAL = 16384;

    record Account(String id, String kind, String username, String uuid) { }
    record Session(String username, String uuid, String accessToken, String userType, String xuid,
                   String clientId) {
        @Override public String toString() { return "Session[username=" + username + ", uuid=" + uuid
                + ", accessToken=<redacted>, userType=" + userType + ", xuid=<redacted>, clientId=<redacted>]"; }
    }
    record Credential(String clientId, String refreshToken) {
        @Override public String toString() { return "Credential[redacted]"; }
    }

    private final Path root;
    private final Path metadata;
    private final Path credentials;
    private final Path pending;
    private final Path runnerRoot;
    private final List<Account> accounts = new ArrayList<>();
    private String defaultId = OFFLINE_ID;

    AccountStore(Path home, Path runnerRoot) throws IOException {
        this.root = home.toAbsolutePath().normalize().resolve("accounts");
        this.metadata = root.resolve("accounts.json");
        this.credentials = root.resolve("credentials");
        this.pending = credentials.resolve("pending-account.json");
        this.runnerRoot = runnerRoot.toAbsolutePath().normalize();
        LauncherFiles.rejectSymlinks(root);
        LauncherFiles.rejectSymlinks(credentials);
        Files.createDirectories(root);
        Files.createDirectories(credentials);
        privateDirectory(root);
        privateDirectory(credentials);
        recoverPending();
        load();
    }

    synchronized List<Account> list() { return List.copyOf(accounts); }
    /** Microsoft accounts are saved only after Minecraft ownership was confirmed (MicrosoftAuthClient). */
    synchronized boolean hasOwnedMinecraft() {
        return accounts.stream().anyMatch(account -> "microsoft".equals(account.kind()));
    }
    synchronized Account get(String id) {
        return accounts.stream().filter(account -> account.id().equals(id)).findFirst().orElse(null);
    }
    synchronized Account defaultAccount() { return get(defaultId); }
    synchronized String defaultAccountId() { return defaultId; }

    synchronized Account resolveAccount(String instanceOverride) throws IOException {
        String id = instanceOverride == null || instanceOverride.isBlank() ? defaultId : instanceOverride;
        Account account = get(id);
        if (account == null) throw new IOException("The selected account no longer exists");
        return account;
    }

    synchronized Account selectDefault(String id) throws IOException {
        Account account = get(id);
        if (account == null) throw new IOException("Unknown account");
        String previous = defaultId;
        defaultId = id;
        try { save(); } catch (IOException failure) { defaultId = previous; throw failure; }
        return account;
    }

    synchronized Account renameOffline(String name) throws IOException {
        String username = validUsername(name);
        Account previous = get(OFFLINE_ID);
        Account changed = offline(username);
        int index = accounts.indexOf(previous);
        accounts.set(index, changed);
        try { save(); } catch (IOException failure) { accounts.set(index, previous); throw failure; }
        return changed;
    }

    synchronized Session offlineSession() {
        Account account = get(OFFLINE_ID);
        return new Session(account.username(), account.uuid(), "0", "legacy", "", "");
    }

    synchronized void putMicrosoft(String uuid, String username, String clientId, String refreshToken)
            throws IOException {
        String id = compactUuid(uuid);
        String cleanName = validUsername(username);
        if (clientId == null || clientId.isBlank() || refreshToken == null || refreshToken.isBlank())
            throw new IOException("Incomplete account credentials");
        Account account = new Account(id, "microsoft", cleanName, id);
        Account previous = get(id);
        byte[] oldCredential = previous == null ? null : existingCredentialBytes(id);
        if (previous != null) accounts.remove(previous);
        accounts.add(account);
        byte[] newMetadata;
        try { newMetadata = metadataBytes(); }
        catch (IOException failure) {
            accounts.remove(account);
            if (previous != null) accounts.add(previous);
            throw failure;
        }
        byte[] newCredential = credentialBytes(id, new Credential(clientId, refreshToken));
        // The private pending record permits crash recovery between the two atomic replacements.
        try { writePending(id, oldCredential, newCredential, newMetadata); }
        catch (IOException failure) {
            accounts.remove(account);
            if (previous != null) accounts.add(previous);
            throw failure;
        }
        try {
            LauncherFiles.write(credentialPath(id), newCredential, true);
            LauncherFiles.write(metadata, newMetadata, false);
        } catch (IOException failure) {
            if (!metadataMatches(newMetadata)) {
                accounts.remove(account);
                if (previous != null) accounts.add(previous);
                try { restoreCredentialIfCurrent(id, oldCredential, newCredential); }
                catch (IOException recovery) { failure.addSuppressed(recovery); throw failure; }
            }
            // If the metadata replacement succeeded, retain the matching new credential.
            Files.deleteIfExists(pending);
            if (!metadataMatches(newMetadata)) throw failure;
            return;
        }
        // A failed marker cleanup is harmless: startup detects the committed metadata digest.
        try { Files.deleteIfExists(pending); } catch (IOException ignored) { }
    }

    synchronized void updateProfile(String id, String username) throws IOException {
        Account old = requireMicrosoft(id);
        Account changed = new Account(id, "microsoft", validUsername(username), id);
        int index = accounts.indexOf(old);
        accounts.set(index, changed);
        try { save(); } catch (IOException failure) { accounts.set(index, old); throw failure; }
    }

    synchronized Credential credential(String id) throws IOException {
        requireMicrosoft(id);
        Path path = credentialPath(id);
        try {
            JsonObject object = JsonParser.parseString(new String(LauncherFiles.read(path, MAX_CREDENTIAL),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            if (object.get("schema").getAsInt() != SCHEMA || !id.equals(object.get("accountId").getAsString()))
                throw new IOException("Invalid saved credentials");
            String clientId = object.get("clientId").getAsString();
            String token = object.get("refreshToken").getAsString();
            if (clientId.isBlank() || token.isBlank()) throw new IOException("Invalid saved credentials");
            return new Credential(clientId, token);
        } catch (RuntimeException failure) { throw new IOException("Saved credentials are invalid; sign in again"); }
    }

    synchronized void rotateCredential(String id, String clientId, String refreshToken) throws IOException {
        requireMicrosoft(id);
        if (refreshToken == null || refreshToken.isBlank()) throw new IOException("Missing rotated credentials");
        writeCredential(id, new Credential(clientId, refreshToken));
    }

    synchronized void clearCredential(String id) throws IOException {
        requireMicrosoft(id);
        Path path = credentialPath(id);
        LauncherFiles.rejectSymlinks(path);
        Files.deleteIfExists(path);
    }

    synchronized void remove(String id) throws IOException {
        if (OFFLINE_ID.equals(id)) throw new IOException("The offline account is required");
        Account old = requireMicrosoft(id);
        // Sign-out revokes local use first, even if metadata persistence subsequently fails.
        clearCredential(id);
        accounts.remove(old);
        String previous = defaultId;
        if (id.equals(defaultId)) defaultId = OFFLINE_ID;
        try { save(); }
        catch (IOException failure) {
            accounts.add(old);
            defaultId = previous;
            throw failure;
        }
    }

    private void load() throws IOException {
        if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
            try {
                JsonObject object = JsonParser.parseString(new String(LauncherFiles.read(metadata, MAX_METADATA),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                if (object.get("schema").getAsInt() != SCHEMA) throw new IOException("Unsupported account schema");
                defaultId = object.get("defaultId").getAsString();
                JsonArray entries = object.getAsJsonArray("accounts");
                for (JsonElement element : entries) {
                    JsonObject entry = element.getAsJsonObject();
                    String id = entry.get("id").getAsString();
                    String kind = entry.get("kind").getAsString();
                    String name = validUsername(entry.get("username").getAsString());
                    String uuid = entry.get("uuid").getAsString();
                    if (OFFLINE_ID.equals(id) && "offline".equals(kind)) accounts.add(offline(name));
                    else if ("microsoft".equals(kind) && id.equals(compactUuid(uuid)))
                        accounts.add(new Account(id, kind, name, id));
                    else throw new IOException("Invalid saved account");
                }
                if (accounts.stream().filter(a -> a.id().equals(OFFLINE_ID)).count() != 1
                        || accounts.stream().map(Account::id).distinct().count() != accounts.size())
                    throw new IOException("Invalid saved account list");
                if (get(defaultId) == null) defaultId = OFFLINE_ID;
                return;
            } catch (RuntimeException failure) { throw new IOException("Saved account metadata is invalid"); }
        }
        accounts.add(offline(migratedUsername()));
        save();
    }

    private String migratedUsername() {
        Path args = runnerRoot.resolve("app-args.txt");
        try {
            String text = new String(LauncherFiles.read(args, 65536), StandardCharsets.UTF_8);
            List<String> values = new ArrayList<>();
            for (String line : text.split("\\R"))
                if (!line.isEmpty() && !line.startsWith("#")) values.add(line);
            for (int i = 0; i + 1 < values.size(); i++)
                if ("--username".equals(values.get(i))) return validUsername(values.get(i + 1));
        } catch (IOException | IllegalArgumentException ignored) { /* first-run fallback */ }
        return "OrbisOffline";
    }

    private void save() throws IOException {
        LauncherFiles.write(metadata, metadataBytes(), false);
    }

    private byte[] metadataBytes() throws IOException {
        JsonObject object = new JsonObject();
        object.addProperty("schema", SCHEMA);
        object.addProperty("defaultId", defaultId);
        JsonArray entries = new JsonArray();
        for (Account account : accounts) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", account.id());
            entry.addProperty("kind", account.kind());
            entry.addProperty("username", account.username());
            entry.addProperty("uuid", account.uuid());
            entries.add(entry);
        }
        object.add("accounts", entries);
        byte[] bytes = (object + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_METADATA) throw new IOException("Too many saved accounts");
        return bytes;
    }

    private void writeCredential(String id, Credential credential) throws IOException {
        LauncherFiles.write(credentialPath(id), credentialBytes(id, credential), true);
    }

    private byte[] credentialBytes(String id, Credential credential) throws IOException {
        JsonObject object = new JsonObject();
        object.addProperty("schema", SCHEMA);
        object.addProperty("accountId", id);
        object.addProperty("clientId", credential.clientId());
        object.addProperty("refreshToken", credential.refreshToken());
        byte[] bytes = (object + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CREDENTIAL) throw new IOException("Credential is too large");
        return bytes;
    }

    private byte[] existingCredentialBytes(String id) throws IOException {
        Path path = credentialPath(id);
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? LauncherFiles.read(path, MAX_CREDENTIAL) : null;
    }

    private void writePending(String id, byte[] oldCredential, byte[] newCredential, byte[] newMetadata)
            throws IOException {
        JsonObject object = new JsonObject();
        object.addProperty("schema", SCHEMA);
        object.addProperty("accountId", id);
        object.addProperty("oldCredential", oldCredential == null ? "" : Base64.getEncoder().encodeToString(oldCredential));
        object.addProperty("hadCredential", oldCredential != null);
        object.addProperty("newCredentialHash", hash(newCredential));
        object.addProperty("newMetadataHash", hash(newMetadata));
        LauncherFiles.write(pending, (object + "\n").getBytes(StandardCharsets.UTF_8), true);
    }

    private void recoverPending() throws IOException {
        if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) return;
        final String id, credentialHash, metadataHash;
        final byte[] oldCredential;
        try {
            JsonObject object = JsonParser.parseString(new String(LauncherFiles.read(pending, 65536),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            if (object.get("schema").getAsInt() != SCHEMA) throw new IOException("Invalid pending account update");
            id = object.get("accountId").getAsString();
            if (!id.matches("[0-9a-f]{32}")) throw new IOException("Invalid pending account update");
            credentialHash = object.get("newCredentialHash").getAsString();
            metadataHash = object.get("newMetadataHash").getAsString();
            oldCredential = object.get("hadCredential").getAsBoolean()
                    ? Base64.getDecoder().decode(object.get("oldCredential").getAsString()) : null;
            if (oldCredential != null && oldCredential.length > MAX_CREDENTIAL)
                throw new IOException("Invalid pending account update");
        } catch (RuntimeException failure) { throw new IOException("Pending account update is invalid"); }
        if (!metadataMatchesHash(metadataHash))
            restoreCredentialIfCurrentHash(id, oldCredential, credentialHash);
        Files.delete(pending);
    }

    private void restoreCredentialIfCurrent(String id, byte[] oldCredential, byte[] newCredential)
            throws IOException {
        restoreCredentialIfCurrentHash(id, oldCredential, hash(newCredential));
    }

    private void restoreCredentialIfCurrentHash(String id, byte[] oldCredential, String newHash)
            throws IOException {
        Path path = credentialPath(id);
        byte[] current = existingCredentialBytes(id);
        // A different credential may be a newer refresh rotation; never overwrite it.
        if (current != null && !hash(current).equals(newHash)) return;
        if (oldCredential == null) {
            LauncherFiles.rejectSymlinks(path);
            Files.deleteIfExists(path);
        } else LauncherFiles.write(path, oldCredential, true);
    }

    private boolean metadataMatches(byte[] expected) throws IOException {
        return metadataMatchesHash(hash(expected));
    }

    private boolean metadataMatchesHash(String expectedHash) throws IOException {
        return Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)
                && hash(LauncherFiles.read(metadata, MAX_METADATA)).equals(expectedHash);
    }

    private static String hash(byte[] bytes) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private Path credentialPath(String id) throws IOException {
        if (!id.matches("[0-9a-f]{32}")) throw new IOException("Invalid account ID");
        return credentials.resolve(id + ".json");
    }

    private Account requireMicrosoft(String id) throws IOException {
        Account account = get(id);
        if (account == null || !"microsoft".equals(account.kind())) throw new IOException("Unknown Microsoft account");
        return account;
    }

    private static Account offline(String username) {
        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        return new Account(OFFLINE_ID, "offline", username, uuid);
    }

    static String compactUuid(String value) throws IOException {
        if (value == null || !value.matches("[0-9a-fA-F]{32}")) throw new IOException("Invalid Minecraft profile ID");
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static String validUsername(String name) {
        if (name == null || name.isBlank() || name.length() > 64 || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid account name");
        return name.strip();
    }

    private static void privateDirectory(Path path) throws IOException {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException | IOException ignored) { /* Console filesystem may lack POSIX attributes. */ }
    }
}
