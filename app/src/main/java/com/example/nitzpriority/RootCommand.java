package com.example.nitzpriority;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Shared root-shell helper: bounded output capture with a hard timeout. */
final class RootCommand {
    private static final int TIMEOUT_SECONDS = 25;
    private static final int MAX_OUTPUT_CHARS = 100_000;

    static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    private RootCommand() { }

    static Result run(String command) {
        Process process = null;
        Thread outputReader = null;
        StringBuilder output = new StringBuilder();
        try {
            process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
            final Process runningProcess = process;
            outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(runningProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            if (output.length() < MAX_OUTPUT_CHARS) output.append(line).append('\n');
                        }
                    }
                } catch (Throwable ignored) {
                    // The process may be terminated after a timeout.
                }
            }, "nitz-root-output");
            outputReader.setDaemon(true);
            outputReader.start();
            boolean completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
            }
            outputReader.join(1_000);
            String captured;
            synchronized (output) {
                captured = output.toString();
            }
            if (!completed) return new Result(-1, "su command timed out\n" + captured);
            return new Result(process.exitValue(), captured);
        } catch (Throwable t) {
            if (process != null) process.destroyForcibly();
            if (outputReader != null) outputReader.interrupt();
            return new Result(-1, t.toString());
        }
    }
}
