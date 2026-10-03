package runner;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * There is no jstack or attach on Orbis, so a hung application cannot otherwise be inspected: while
 * the runner is up, creating {@code <runner root>/dump-threads} (e.g. over FTP) makes this daemon
 * delete the trigger and append every thread's full stack, with lock owners, to
 * {@code <runner root>/thread-dump.txt}.
 *
 * <p>{@code <runner root>/profile-threads} instead samples every thread's stack every 20 ms for 15
 * seconds (or the number of seconds written in the trigger, 1-120) and writes each busy thread's
 * hottest methods, by self and by total samples, to {@code <runner root>/profile.txt}. Samples
 * are taken at safepoints, so they are biased toward safepoint polls, but they show where time
 * goes without any agent.
 */
final class ThreadDumpWatcher implements Runnable {
    private static final long SAMPLE_INTERVAL_MS = 20;
    private static final int TOP = 30;

    private final Path trigger;
    private final Path output;
    private final Path profileTrigger;
    private final Path profileOutput;

    private ThreadDumpWatcher(Path root) {
        trigger = root.resolve("dump-threads");
        output = root.resolve("thread-dump.txt");
        profileTrigger = root.resolve("profile-threads");
        profileOutput = root.resolve("profile.txt");
    }

    static void start(Path root) {
        Thread thread = new Thread(new ThreadDumpWatcher(root), "runner thread-dump watcher");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(2000);
                if (Files.deleteIfExists(trigger))
                    Files.writeString(output, dump(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND, StandardOpenOption.WRITE);
                if (Files.exists(profileTrigger)) {
                    int seconds = profileSeconds(Files.readString(profileTrigger, StandardCharsets.UTF_8));
                    Files.deleteIfExists(profileTrigger);
                    Files.writeString(profileOutput, profile(seconds), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE);
                }
            } catch (InterruptedException interrupted) {
                return;
            } catch (IOException | RuntimeException failure) {
                System.err.println("runner: thread dump failed: " + failure);
            }
        }
    }

    private static int profileSeconds(String text) {
        try {
            int seconds = Integer.parseInt(text.trim());
            return Math.max(1, Math.min(120, seconds));
        } catch (NumberFormatException empty) {
            return 15;
        }
    }

    /** Per-thread sample counts: by state, by top frame (self) and by any frame (total). */
    private static final class ThreadProfile {
        final String name;
        int samples;
        final Map<Thread.State, Integer> states = new HashMap<>();
        final Map<String, Integer> self = new HashMap<>();
        final Map<String, Integer> total = new HashMap<>();

        ThreadProfile(String name) {
            this.name = name;
        }
    }

    private static String profile(int seconds) {
        Map<Long, ThreadProfile> threads = new HashMap<>();
        long started = System.nanoTime();
        long end = started + seconds * 1_000_000_000L;
        int rounds = 0;
        long sampling = 0;
        while (System.nanoTime() < end) {
            long before = System.nanoTime();
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                if (thread == Thread.currentThread()) continue;
                StackTraceElement[] frames = entry.getValue();
                ThreadProfile profile = threads.computeIfAbsent(thread.getId(),
                        id -> new ThreadProfile(thread.getName()));
                profile.samples++;
                profile.states.merge(thread.getState(), 1, Integer::sum);
                if (frames.length == 0) continue;
                profile.self.merge(method(frames[0]), 1, Integer::sum);
                Set<String> seen = new HashSet<>();
                for (StackTraceElement frame : frames)
                    if (seen.add(method(frame))) profile.total.merge(method(frame), 1, Integer::sum);
            }
            sampling += System.nanoTime() - before;
            rounds++;
            try {
                Thread.sleep(SAMPLE_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        StringWriter text = new StringWriter();
        PrintWriter out = new PrintWriter(text);
        out.printf("==== profile %s: %d rounds over %.1f s, %.2f ms per round spent sampling%n", Instant.now(),
                rounds, (System.nanoTime() - started) / 1e9, rounds == 0 ? 0.0 : sampling / 1e6 / rounds);
        List<ThreadProfile> busy = new ArrayList<>(threads.values());
        busy.sort((a, b) -> Integer.compare(running(b), running(a)));
        for (ThreadProfile profile : busy) {
            if (running(profile) == 0 || profile.self.isEmpty()) continue;
            out.printf("%n\"%s\" %d samples, states %s%n", profile.name, profile.samples, profile.states);
            out.println("  self:");
            top(out, profile.self, profile.samples);
            out.println("  total:");
            top(out, profile.total, profile.samples);
        }
        out.println();
        out.println("idle threads (never RUNNABLE):");
        for (ThreadProfile profile : busy)
            if (running(profile) == 0) out.println("  " + profile.name + " " + profile.states);
        out.flush();
        return text.toString();
    }

    private static int running(ThreadProfile profile) {
        return profile.states.getOrDefault(Thread.State.RUNNABLE, 0);
    }

    private static String method(StackTraceElement frame) {
        return frame.getClassName() + "." + frame.getMethodName()
                + (frame.isNativeMethod() ? " (native)" : "");
    }

    private static void top(PrintWriter out, Map<String, Integer> counts, int samples) {
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(TOP)
                .forEach(entry -> out.printf("    %5.1f%%  %6d  %s%n", 100.0 * entry.getValue() / samples,
                        entry.getValue(), entry.getKey()));
    }

    private static String dump() {
        StringWriter text = new StringWriter();
        PrintWriter out = new PrintWriter(text);
        out.println("==== thread dump " + Instant.now());
        try {
            for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
                out.print("\"" + info.getThreadName() + "\" #" + info.getThreadId() + " " + info.getThreadState());
                if (info.getLockName() != null) out.print(" on " + info.getLockName());
                if (info.getLockOwnerName() != null) out.print(" owned by \"" + info.getLockOwnerName() + "\"");
                out.println();
                StackTraceElement[] frames = info.getStackTrace();
                MonitorInfo[] monitors = info.getLockedMonitors();
                for (int i = 0; i < frames.length; i++) {
                    out.println("\tat " + frames[i]);
                    for (MonitorInfo monitor : monitors)
                        if (monitor.getLockedStackDepth() == i) out.println("\t- locked " + monitor);
                }
                for (LockInfo lock : info.getLockedSynchronizers()) out.println("\t- holds " + lock);
                out.println();
            }
        } catch (LinkageError | UnsupportedOperationException noManagement) {
            out.println("(java.management unavailable: " + noManagement + "; stacks only)");
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                out.println("\"" + entry.getKey().getName() + "\" " + entry.getKey().getState());
                for (StackTraceElement frame : entry.getValue()) out.println("\tat " + frame);
                out.println();
            }
        }
        out.flush();
        return text.toString();
    }
}
