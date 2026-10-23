package com.naocraftlab.skins.buildlogic

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

final class CompositionProcess {
    static int await(Process process, long timeoutMillis, long cleanupMillis) {
        Throwable primary
        boolean interrupted = false
        int exit = -1
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS))
                throw new TimeoutException('Composition child exceeded execution budget')
            exit = process.exitValue()
        } catch (Throwable failure) {
            primary = failure
            interrupted = failure instanceof InterruptedException
        } finally {
            interrupted |= Thread.interrupted()
            Throwable cleanupFailure
            try {
                if (process.isAlive()) {
                    process.destroy()
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cleanupMillis)
                    boolean forced = false
                    while (process.isAlive() && System.nanoTime() < deadline) {
                        if (!forced) { process.destroyForcibly(); forced = true }
                        try {
                            process.waitFor(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                        } catch (InterruptedException failure) {
                            interrupted = true
                            if (primary == null) primary = failure
                            else primary.addSuppressed(failure)
                        }
                    }
                    if (process.isAlive()) throw new IllegalStateException('Composition child termination unconfirmed')
                }
            } catch (Throwable failure) { cleanupFailure = failure }
            if (cleanupFailure != null) {
                if (primary == null) primary = cleanupFailure
                else primary.addSuppressed(cleanupFailure)
            }
            if (interrupted) {
                if (primary == null) primary = new InterruptedException('Composition cleanup interrupted')
                Thread.currentThread().interrupt()
            }
        }
        if (primary != null) throw primary
        exit
    }
}
