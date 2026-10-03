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
import java.util.Map;

/**
 * There is no jstack or attach on Orbis, so a hung application cannot otherwise be inspected: while
 * the runner is up, creating {@code <runner root>/dump-threads} (e.g. over FTP) makes this daemon
 * delete the trigger and append every thread's full stack, with lock owners, to
 * {@code <runner root>/thread-dump.txt}.
 */
final class ThreadDumpWatcher implements Runnable {
    private final Path trigger;
    private final Path output;

    private ThreadDumpWatcher(Path root) {
        trigger = root.resolve("dump-threads");
        output = root.resolve("thread-dump.txt");
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
            } catch (InterruptedException interrupted) {
                return;
            } catch (IOException | RuntimeException failure) {
                System.err.println("runner: thread dump failed: " + failure);
            }
        }
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
