package com.naocraftlab.skins.buildlogic

import java.util.concurrent.TimeUnit

final class ReleaseProcess {
    static void run(ProcessBuilder builder, String label, long timeoutSeconds, Closure output) {
        Process process = builder.redirectErrorStream(true).start()
        Thread cleanup = new Thread({ terminate(process) }, "${label}-cleanup")
        Runtime.runtime.addShutdownHook(cleanup)
        Thread reader = new Thread({
            process.inputStream.withReader('UTF-8') { stream ->
                stream.eachLine { output.call("[${label}] ${it}".toString()) }
            }
        }, "${label}-output")
        reader.daemon = true
        reader.start()
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException("${label} exceeded ${timeoutSeconds}s; terminating process tree")
            }
            reader.join(5000)
            if (process.exitValue() != 0) {
                throw new IllegalStateException("${label} failed (${process.exitValue()}); see streamed output")
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt()
            throw new IllegalStateException("${label} cancelled", error)
        } finally {
            terminate(process)
            try { Runtime.runtime.removeShutdownHook(cleanup) } catch (IllegalStateException ignored) { }
        }
    }

    private static void terminate(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList()
        descendants.reverseEach { it.destroy() }
        process.destroy()
        descendants.reverseEach { if (it.isAlive()) it.destroyForcibly() }
        if (process.isAlive()) process.destroyForcibly()
    }
}
