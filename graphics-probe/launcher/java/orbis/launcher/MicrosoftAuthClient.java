package orbis.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import javax.net.ssl.HttpsURLConnection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Microsoft public-client device flow and the Xbox/Minecraft Services token exchange. */
final class MicrosoftAuthClient {
    private static final String DEVICE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBOX_USER_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/launcher/login";
    private static final String ENTITLEMENTS_URL = "https://api.minecraftservices.com/entitlements/license";
    private static final String PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final int MAX_RESPONSE = 1024 * 1024;
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 15000;

    enum Category { CONFIGURATION, REGISTRATION, DENIED, EXPIRED, SIGN_IN_REQUIRED, RESTRICTED_ACCOUNT,
                    NO_OWNERSHIP, MISSING_PROFILE, RETRY_LATER, NETWORK, PROTOCOL }

    static final class AuthException extends IOException {
        private static final long serialVersionUID = 1L;
        private final Category category;
        private final String stage;
        private final int httpStatus;

        AuthException(Category category, String stage, int httpStatus, String message) {
            super(message);
            this.category = category;
            this.stage = stage;
            this.httpStatus = httpStatus;
        }
        Category category() { return category; }
        String stage() { return stage; }
        int httpStatus() { return httpStatus; }
        @Override public String toString() { return "AuthException[" + category + ", stage=" + stage
                + ", httpStatus=" + httpStatus + "]: " + getMessage(); }
    }

    /** The device code remains private to the network client and is never in the UI record. */
    static final class DeviceSession {
        private final String verificationUri;
        private final String userCode;
        private final Instant expiresAt;
        private final String deviceCode;
        private final String clientId;
        private final Cancellation cancellation = new Cancellation();
        private Duration pollInterval;
        private Instant nextPoll;

        private DeviceSession(String verificationUri, String userCode, Instant expiresAt, Duration interval,
                              String deviceCode, String clientId) {
            this.verificationUri = verificationUri;
            this.userCode = userCode;
            this.expiresAt = expiresAt;
            this.pollInterval = interval;
            this.nextPoll = Instant.now().plus(interval);
            this.deviceCode = deviceCode;
            this.clientId = clientId;
        }
        String verificationUri() { return verificationUri; }
        String userCode() { return userCode; }
        Instant expiresAt() { return expiresAt; }
        synchronized Duration pollInterval() { return pollInterval; }
        synchronized Instant nextPollAt() { return nextPoll; }
        boolean cancelled() { return cancellation.cancelled(); }
        @Override public String toString() { return "DeviceSession[verificationUri=" + verificationUri
                + ", userCode=<redacted>, expiresAt=" + expiresAt + ", deviceCode=<redacted>]"; }
    }

    /** A Play worker may cancel a refresh and close its current HTTP request. */
    static final class Cancellation {
        private volatile boolean cancelled;
        private volatile HttpsURLConnection active;
        synchronized void cancel() {
            cancelled = true;
            HttpsURLConnection connection = active;
            if (connection != null) connection.disconnect();
        }
        boolean cancelled() { return cancelled; }
        private void check() {
            if (cancelled || Thread.currentThread().isInterrupted())
                throw new CancellationException("Authentication cancelled");
        }
        synchronized void saveLogin(AccountStore accounts, Exchange result, String clientId,
                                    String refreshToken) throws IOException {
            check();
            accounts.putMicrosoft(result.profile.id, result.profile.name, clientId, refreshToken);
        }
    }

    private record Reply(int status, JsonObject body) {
        @Override public String toString() { return "Reply[status=" + status + ", body=<redacted>]"; }
    }
    private record Tokens(String access, String refresh) {
        @Override public String toString() { return "Tokens[redacted]"; }
    }
    private record XboxToken(String token, String uhs, String xuid) {
        @Override public String toString() { return "XboxToken[redacted]"; }
    }
    private record Profile(String id, String name) { }

    private final AccountStore accounts;
    private final Path configuration;

    MicrosoftAuthClient(AccountStore accounts, Path home) {
        this.accounts = accounts;
        this.configuration = home.toAbsolutePath().normalize().resolve("microsoft-auth.properties");
    }

    DeviceSession beginLogin() throws AuthException {
        String clientId = clientId();
        JsonObject response = successful(request("device authorization", DEVICE_URL, "POST", "application/x-www-form-urlencoded",
                form("client_id", clientId, "scope", SCOPE), null, new Cancellation()), "device authorization");
        try {
            String uri = required(response, "verification_uri");
            URI parsed = URI.create(uri);
            String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getUserInfo() != null
                    || parsed.getFragment() != null || parsed.getPort() != -1
                    || !(host.equals("microsoft.com") || host.endsWith(".microsoft.com")
                    || host.equals("microsoftonline.com") || host.endsWith(".microsoftonline.com")
                    || host.equals("msauth.net") || host.endsWith(".msauth.net")))
                throw new IllegalArgumentException("Invalid verification URI");
            long expires = response.get("expires_in").getAsLong();
            long interval = response.has("interval") ? response.get("interval").getAsLong() : 5;
            if (expires < 1 || expires > 3600 || interval < 1 || interval > 300)
                throw new IllegalArgumentException("Invalid polling limits");
            return new DeviceSession(uri, required(response, "user_code"), Instant.now().plusSeconds(expires),
                    Duration.ofSeconds(interval), required(response, "device_code"), clientId);
        } catch (RuntimeException failure) { throw error(Category.PROTOCOL, "device authorization", 200,
                "Microsoft returned an invalid sign-in request. Try again."); }
    }

    /** Returns null while authorization is pending or the poll interval has not elapsed. */
    AccountStore.Session pollLogin(DeviceSession session) throws AuthException, IOException {
        if (session == null) throw error(Category.PROTOCOL, "device polling", 0, "No sign-in is in progress.");
        session.cancellation.check();
        synchronized (session) {
            if (!Instant.now().isBefore(session.expiresAt))
                throw error(Category.EXPIRED, "device polling", 0, "The sign-in code expired. Start again.");
            if (Instant.now().isBefore(session.nextPoll)) return null;
            // Reserve the next slot before the request, preventing concurrent callers from overpolling.
            session.nextPoll = Instant.now().plus(session.pollInterval);
        }
        Reply reply = request("device polling", TOKEN_URL, "POST", "application/x-www-form-urlencoded",
                form("grant_type", "urn:ietf:params:oauth:grant-type:device_code", "client_id", session.clientId,
                        "device_code", session.deviceCode), null, session.cancellation);
        if (reply.status != 200) {
            String oauth = optional(reply.body, "error");
            System.err.println("launcher: auth device polling answer: " + (oauth.isEmpty() ? "(none)" : oauth));
            if ("authorization_pending".equals(oauth)) return null;
            if ("slow_down".equals(oauth)) {
                synchronized (session) {
                    session.pollInterval = session.pollInterval.plusSeconds(5);
                    session.nextPoll = Instant.now().plus(session.pollInterval);
                }
                return null;
            }
            throw oauthError("device polling", reply);
        }
        Tokens tokens = tokens(reply.body, "device polling", true);
        session.cancellation.check();
        Exchange result = exchange(tokens.access, session.cancellation);
        session.cancellation.check();
        try { session.cancellation.saveLogin(accounts, result, session.clientId, tokens.refresh); }
        catch (IOException failure) { throw error(Category.PROTOCOL, "account storage", 0,
                "Could not save the signed-in account. Try again."); }
        return new AccountStore.Session(result.profile.name, result.profile.id, result.minecraftToken,
                "msa", result.xuid, session.clientId);
    }

    void cancelLogin(DeviceSession session) { if (session != null) session.cancellation.cancel(); }

    AccountStore.Session refresh(String accountId) throws IOException { return refresh(accountId, new Cancellation()); }

    AccountStore.Session refresh(String accountId, Cancellation cancellation) throws IOException {
        cancellation.check();
        String configured = clientId();
        AccountStore.Credential credential;
        try { credential = accounts.credential(accountId); }
        catch (IOException failure) { throw error(Category.SIGN_IN_REQUIRED, "refresh", 0,
                "Saved sign-in is unavailable. Sign in again."); }
        if (!configured.equals(credential.clientId())) throw error(Category.CONFIGURATION, "refresh", 0,
                "This account was saved with a different Microsoft client ID. Restore that configuration or sign in again.");
        Reply reply = request("refresh", TOKEN_URL, "POST", "application/x-www-form-urlencoded",
                form("grant_type", "refresh_token", "client_id", configured,
                        "refresh_token", credential.refreshToken(), "scope", SCOPE), null, cancellation);
        if (reply.status != 200) {
            String oauth = optional(reply.body, "error");
            if ("invalid_grant".equals(oauth) || "interaction_required".equals(oauth)) {
                try { accounts.clearCredential(accountId); }
                catch (IOException failure) { throw error(Category.PROTOCOL, "refresh storage", 0,
                        "Could not clear expired sign-in. Remove this account locally."); }
            }
            throw oauthError("refresh", reply);
        }
        Tokens tokens = tokens(reply.body, "refresh", false);
        // A rotated token is saved before downstream calls, which may fail independently.
        if (!tokens.refresh.isBlank()) {
            try { accounts.rotateCredential(accountId, configured, tokens.refresh); }
            catch (IOException failure) { throw error(Category.PROTOCOL, "refresh storage", 0,
                    "Could not save refreshed sign-in. Try again."); }
        }
        cancellation.check();
        Exchange result = exchange(tokens.access, cancellation);
        if (!accountId.equals(result.profile.id)) throw error(Category.SIGN_IN_REQUIRED, "profile", 0,
                "The Microsoft account resolved to a different Minecraft profile. Sign in again.");
        cancellation.check();
        try { accounts.updateProfile(accountId, result.profile.name); }
        catch (IOException failure) { throw error(Category.PROTOCOL, "account storage", 0,
                "Could not update the saved account profile."); }
        return new AccountStore.Session(result.profile.name, result.profile.id, result.minecraftToken,
                "msa", result.xuid, configured);
    }

    private record Exchange(Profile profile, String minecraftToken, String xuid) {
        @Override public String toString() { return "Exchange[profile=" + profile + ", credentials=<redacted>]"; }
    }

    private Exchange exchange(String microsoftAccess, Cancellation cancellation) throws AuthException {
        cancellation.check();
        JsonObject userRequest = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", "d=" + microsoftAccess);
        userRequest.add("Properties", properties);
        userRequest.addProperty("RelyingParty", "http://auth.xboxlive.com");
        userRequest.addProperty("TokenType", "JWT");
        XboxToken user = xboxToken(successful(request("Xbox user", XBOX_USER_URL, "POST", "application/json",
                userRequest.toString(), null, cancellation), "Xbox user"), "Xbox user");
        XboxToken xbox = xsts(user, "http://xboxlive.com", cancellation);
        XboxToken minecraft = xsts(user, "rp://api.minecraftservices.com/", cancellation);
        if (!user.uhs.equals(xbox.uhs) || !user.uhs.equals(minecraft.uhs))
            throw error(Category.PROTOCOL, "Xbox authorization", 0, "Xbox account identity changed during sign-in.");
        JsonObject login = new JsonObject();
        login.addProperty("xtoken", "XBL3.0 x=" + minecraft.uhs + ";" + minecraft.token);
        login.addProperty("platform", "PC_LAUNCHER");
        JsonObject loginResult = successful(request("Minecraft login", MC_LOGIN_URL, "POST", "application/json",
                login.toString(), null, cancellation), "Minecraft login");
        String mcToken = safeRequired(loginResult, "access_token", "Minecraft login");
        Reply ownedReply = request("ownership", ENTITLEMENTS_URL, "GET", null, null, mcToken, cancellation);
        if (ownedReply.status == 404) throw error(Category.NO_OWNERSHIP, "ownership", 404,
                "This Microsoft account does not own Minecraft Java Edition.");
        JsonObject owned = successful(ownedReply, "ownership");
        if (!ownsMinecraft(owned)) throw error(Category.NO_OWNERSHIP, "ownership", 200,
                "This Microsoft account does not own Minecraft Java Edition.");
        Reply profileReply = request("profile", PROFILE_URL, "GET", null, null, mcToken, cancellation);
        if (profileReply.status == 404) throw error(Category.MISSING_PROFILE, "profile", 404,
                "Create a Minecraft Java profile for this account, then try again.");
        JsonObject profileJson = successful(profileReply, "profile");
        Profile profile;
        try {
            String id = AccountStore.compactUuid(required(profileJson, "id"));
            String name = required(profileJson, "name");
            if (name.isBlank() || name.length() > 64) throw new IllegalArgumentException("Invalid profile name");
            profile = new Profile(id, name);
        } catch (IOException | RuntimeException failure) { throw error(Category.MISSING_PROFILE, "profile", 200,
                "Minecraft returned an invalid profile. Try again."); }
        cancellation.check();
        return new Exchange(profile, mcToken, xbox.xuid);
    }

    private XboxToken xsts(XboxToken user, String relyingParty, Cancellation cancellation) throws AuthException {
        JsonObject request = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.addProperty("SandboxId", "RETAIL");
        JsonArray tokens = new JsonArray();
        tokens.add(user.token);
        properties.add("UserTokens", tokens);
        request.add("Properties", properties);
        request.addProperty("RelyingParty", relyingParty);
        request.addProperty("TokenType", "JWT");
        return xboxToken(successful(request("Xbox authorization", XSTS_URL, "POST", "application/json",
                request.toString(), null, cancellation), "Xbox authorization"), "Xbox authorization");
    }

    private static XboxToken xboxToken(JsonObject response, String stage) throws AuthException {
        try {
            JsonObject xui = response.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject();
            return new XboxToken(required(response, "Token"), required(xui, "uhs"), optional(xui, "xid"));
        } catch (RuntimeException failure) { throw error(Category.PROTOCOL, stage, 200,
                "Xbox returned an incomplete sign-in response."); }
    }

    private static boolean ownsMinecraft(JsonObject response) {
        try {
            for (JsonElement entry : response.getAsJsonArray("items")) {
                String name = optional(entry.getAsJsonObject(), "name");
                if ("game_minecraft".equals(name) || "product_minecraft".equals(name)) return true;
            }
        } catch (RuntimeException ignored) { /* Invalid ownership data is not proof of entitlement. */ }
        return false;
    }

    /** Written by graphics-probe/tools/launcher_secrets.py at build time; absent in development builds. */
    private static final String EMBEDDED = "msa-client.bin";
    private static final byte[] KEY = {0x5f, 0x1c, (byte) 0xa7, (byte) 0xe2, 0x09, 0x3b, (byte) 0xd4, 0x71,
            (byte) 0x8e, 0x26, (byte) 0xc0, 0x5b, (byte) 0xa4, (byte) 0xf3, 0x19, 0x6d};
    private static final String GUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    /** The application ID built into the launcher, or null when this build has none. */
    static String embeddedClientId() {
        try (java.io.InputStream in = MicrosoftAuthClient.class.getResourceAsStream(EMBEDDED)) {
            if (in == null) return null;
            byte[] data = in.readNBytes(64);
            for (int i = 0; i < data.length; i++) data[i] ^= (byte) (KEY[i % KEY.length] ^ (i * 31));
            String id = new String(data, java.nio.charset.StandardCharsets.US_ASCII);
            return id.matches(GUID) ? id : null;
        } catch (IOException failure) {
            return null;
        }
    }

    private String clientId() throws AuthException {
        String embedded = embeddedClientId();
        if (embedded != null) return embedded;
        try {
            Properties values = new Properties();
            values.load(new java.io.ByteArrayInputStream(LauncherFiles.read(configuration, 8192)));
            if (values.containsKey("client_secret")) throw error(Category.CONFIGURATION, "configuration", 0,
                    "Remove client_secret from microsoft-auth.properties; device sign-in uses a public client.");
            String id = values.getProperty("client_id", "").strip();
            if (!id.matches(GUID))
                throw error(Category.CONFIGURATION, "configuration", 0,
                    "Set client_id in launcher/microsoft-auth.properties to your Microsoft public app ID.");
            return id;
        } catch (AuthException failure) { throw failure; }
        catch (IOException | RuntimeException failure) { throw error(Category.CONFIGURATION, "configuration", 0,
                "Create launcher/microsoft-auth.properties with your Microsoft public app client_id."); }
    }

    private static Tokens tokens(JsonObject object, String stage, boolean requireRefresh) throws AuthException {
        try {
            String access = required(object, "access_token");
            String refresh = optional(object, "refresh_token");
            if (requireRefresh && refresh.isBlank()) throw new IllegalArgumentException("No refresh token");
            return new Tokens(access, refresh);
        } catch (RuntimeException failure) { throw error(Category.PROTOCOL, stage, 200,
                "Microsoft returned incomplete sign-in credentials. Try again."); }
    }

    private static JsonObject successful(Reply reply, String stage) throws AuthException {
        if (reply.status == 200) return reply.body;
        if ("Xbox authorization".equals(stage) && reply.body.has("XErr")) {
            try {
                long code = reply.body.get("XErr").getAsLong();
                if (code == 2148916227L) throw error(Category.RESTRICTED_ACCOUNT, stage, reply.status,
                        "This Xbox account is banned.");
                if (code == 2148916229L) throw error(Category.RESTRICTED_ACCOUNT, stage, reply.status,
                        "A parent or guardian must allow online play for this Xbox account.");
                if (code == 2148916234L) throw error(Category.SIGN_IN_REQUIRED, stage, reply.status,
                        "Accept the Xbox terms of use on xbox.com, then sign in again.");
                if (code == 2148916233L) throw error(Category.SIGN_IN_REQUIRED, stage, reply.status,
                        "This Microsoft account needs an Xbox profile before Minecraft sign-in.");
                if (code == 2148916235L || code == 2148916236L || code == 2148916237L || code == 2148916238L)
                    throw error(Category.RESTRICTED_ACCOUNT, stage, reply.status,
                            "Xbox account or family settings restrict this sign-in.");
            } catch (RuntimeException ignored) { /* Safe generic category below. */ }
        }
        if (reply.status == 429 || reply.status >= 500) throw error(Category.RETRY_LATER, stage, reply.status,
                stage + " is temporarily unavailable. Try again later.");
        if (reply.status == 401 || reply.status == 403) throw error(Category.SIGN_IN_REQUIRED, stage, reply.status,
                stage + " rejected this sign-in. Sign in again.");
        throw error(Category.PROTOCOL, stage, reply.status, stage + " could not complete (HTTP " + reply.status + ").");
    }

    private static AuthException oauthError(String stage, Reply reply) {
        String code = optional(reply.body, "error");
        return switch (code) {
            case "authorization_declined", "access_denied" -> error(Category.DENIED, stage, reply.status,
                    "Microsoft sign-in was declined. Start again.");
            case "expired_token", "bad_verification_code" -> error(Category.EXPIRED, stage, reply.status,
                    "The sign-in code expired. Start again.");
            case "invalid_grant", "interaction_required" -> error(Category.SIGN_IN_REQUIRED, stage, reply.status,
                    "Microsoft sign-in expired. Sign in again.");
            case "invalid_client", "unauthorized_client", "invalid_scope" -> error(Category.REGISTRATION, stage,
                    reply.status, "The Microsoft app registration cannot use this sign-in flow or scope.");
            default -> reply.status == 429 || reply.status >= 500
                    ? error(Category.RETRY_LATER, stage, reply.status, "Microsoft sign-in is temporarily unavailable.")
                    : error(Category.PROTOCOL, stage, reply.status,
                            "Microsoft sign-in failed (HTTP " + reply.status + ").");
        };
    }

    private static Reply request(String stage, String endpoint, String method, String contentType, String body,
                                 String bearer, Cancellation cancellation) throws AuthException {
        cancellation.check();
        HttpsURLConnection connection = null;
        long started = System.nanoTime();
        System.err.println("launcher: auth " + stage + " ...");
        try {
            connection = (HttpsURLConnection) URI.create(endpoint).toURL().openConnection();
            cancellation.active = connection;
            cancellation.check();
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            if (contentType != null) connection.setRequestProperty("Content-Type", contentType + "; charset=UTF-8");
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            if (endpoint.equals(XBOX_USER_URL) || endpoint.equals(XSTS_URL))
                connection.setRequestProperty("x-xbl-contract-version", "1");
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_RESPONSE) throw new IOException("Request is too large");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            // Stage, status and time only: never the request, the reply, or a token.
            System.err.println("launcher: auth " + stage + " -> HTTP " + status + " in "
                    + (System.nanoTime() - started) / 1_000_000 + " ms");
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) return new Reply(status, new JsonObject());
            byte[] data;
            try (InputStream stream = input) { data = readBounded(stream); }
            JsonObject json;
            try { json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch (RuntimeException invalid) {
                if (status != 200) json = new JsonObject();
                else throw error(Category.PROTOCOL, stage, status, stage + " returned an invalid response.");
            }
            return new Reply(status, json);
        } catch (AuthException failure) { throw failure; }
        catch (IOException | IllegalArgumentException failure) {
            System.err.println("launcher: auth " + stage + " failed after " + (System.nanoTime() - started) / 1_000_000
                    + " ms: " + failure.getClass().getSimpleName());
            cancellation.check();
            throw error(Category.NETWORK, stage, 0, stage + " connection failed. Check the network and try again.");
        } finally {
            cancellation.active = null;
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > MAX_RESPONSE) throw new IOException("Response is too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String form(String... values) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length; i += 2) {
            if (i != 0) result.append('&');
            result.append(URLEncoder.encode(values[i], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(values[i + 1], StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    private static String required(JsonObject object, String name) {
        String value = object.get(name).getAsString();
        if (value.isBlank()) throw new IllegalArgumentException("Missing field");
        return value;
    }
    private static String safeRequired(JsonObject object, String name, String stage) throws AuthException {
        try { return required(object, name); }
        catch (RuntimeException failure) { throw error(Category.PROTOCOL, stage, 200,
                stage + " returned an incomplete response."); }
    }
    private static String optional(JsonObject object, String name) {
        try { return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : ""; }
        catch (RuntimeException failure) { return ""; }
    }
    private static AuthException error(Category category, String stage, int status, String message) {
        return new AuthException(category, stage, status, message);
    }
}
