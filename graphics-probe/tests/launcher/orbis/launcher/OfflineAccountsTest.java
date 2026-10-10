package orbis.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline sessions must never become an independent Minecraft ownership grant. */
public final class OfflineAccountsTest {
    private OfflineAccountsTest() { }

    public static void main(String[] args) throws Exception {
        run(args.length == 0 ? Files.createTempDirectory("offline-accounts-") : Path.of(args[0]));
        System.out.println("Premium-gated offline accounts passed");
    }

    static void run(Path root) throws Exception {
        Path home = root.resolve("home"), runner = root.resolve("runner");
        AccountStore store = new AccountStore(home, runner);
        rejected(() -> add(store, "LocalPlayer"), "fresh installation must reject offline creation");
        store.putMicrosoft("0123456789abcdef0123456789abcdef", "Owner", "client", "refresh");
        AccountStore.Account offline = add(store, "LocalPlayer");
        check(offline.kind().equals("offline"), "creates an explicit offline profile");
        check(offline.uuid().equals("c6d762506d42328ebfce7a3312675f70"), "uses Minecraft's standard offline UUID");
        rejected(() -> add(store, "LocalPlayer"), "duplicate offline names are rejected");
        check(!offline.uuid().equals(store.defaultAccount().uuid()), "offline UUID is separate from premium identity");
        rejected(() -> add(store, "bad name"), "offline names must be valid Minecraft names");
        store.selectDefault(offline.id());
        AccountStore reopened = new AccountStore(home, runner);
        check(reopened.resolveAccount("").id().equals(offline.id()), "offline selection survives restart");
        // No Microsoft configuration exists: this must succeed without contacting authentication services.
        AccountStore.Session session = new MicrosoftAuthClient(reopened, home).refresh(offline.id());
        check(session.username().equals("LocalPlayer") && session.uuid().equals(offline.uuid()),
                "offline launch uses its own identity");
        check(session.accessToken().equals("0") && session.userType().equals("legacy")
                && session.xuid().isEmpty() && session.clientId().isEmpty(), "no premium tokens in offline sessions");
        check(LaunchRequest.decode(LaunchRequest.create("instance", offline.id()).encode()).accountId().equals(offline.id()),
                "offline identity survives the restart request");
        reopened.putMicrosoft("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "OtherOwner", "client", "refresh2");
        reopened.remove("0123456789abcdef0123456789abcdef");
        check(new MicrosoftAuthClient(reopened, home).refresh(offline.id()).username().equals("LocalPlayer"),
                "one remaining verified owner keeps offline sessions enabled");
        reopened.clearCredential("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        check(!reopened.hasOwnedMinecraft(), "metadata without saved credentials cannot unlock offline play or installs");
        rejected(() -> new MicrosoftAuthClient(reopened, home).refresh(offline.id()), "credential revocation blocks offline play");
        rejected(() -> reopened.selectDefault(offline.id()), "cannot select offline after losing ownership");
        rejected(() -> add(reopened, "Another"), "offline profiles do not unlock more offline accounts");
        reopened.putMicrosoft("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "OtherOwner", "client", "refresh3");
        reopened.remove("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        check(!reopened.hasOwnedMinecraft(), "last premium sign-out revokes ownership");
        rejected(() -> new MicrosoftAuthClient(reopened, home).refresh(offline.id()), "last premium sign-out blocks offline launch");
        AccountStore afterSignOut = new AccountStore(home, runner);
        check(afterSignOut.defaultAccount() == null, "last sign-out leaves no usable selected account");
        rejected(() -> add(afterSignOut, "NewPlayer"), "restart cannot restore offline eligibility");

        Path failureHome = root.resolve("storage-failure");
        AccountStore failing = new AccountStore(failureHome, runner);
        failing.putMicrosoft("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Owner", "client", "refresh");
        AccountStore.Account failedOffline = add(failing, "LocalPlayer");
        failing.selectDefault(failedOffline.id());
        Path metadata = failureHome.resolve("accounts/accounts.json");
        byte[] previousMetadata = Files.readAllBytes(metadata);
        Files.delete(metadata);
        Files.createDirectory(metadata); // Force metadata replacement to fail after credentials are deleted.
        rejected(() -> failing.remove("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), "sign-out reports storage failure");
        rejected(() -> new MicrosoftAuthClient(failing, failureHome).refresh(failedOffline.id()),
                "failed metadata save cannot restore offline eligibility");
        Files.delete(metadata);
        Files.write(metadata, previousMetadata);
        AccountStore recovered = new AccountStore(failureHome, runner);
        rejected(() -> new MicrosoftAuthClient(recovered, failureHome).refresh(failedOffline.id()),
                "restart after partial sign-out cannot restore offline eligibility");
    }

    private static AccountStore.Account add(AccountStore store, String name) throws IOException {
        return store.putOffline(name);
    }

    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void rejected(Operation action, String message) throws Exception {
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
