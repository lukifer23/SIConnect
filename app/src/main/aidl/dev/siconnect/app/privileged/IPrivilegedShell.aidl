package dev.siconnect.app.privileged;

import android.os.ParcelFileDescriptor;

// Runs inside the Shizuku UserService process (shell uid 2000 on non-root Shizuku).
// Payloads are JSON strings to keep the binder contract stable across app versions.
interface IPrivilegedShell {
    // Reserved by Shizuku for UserService teardown.
    void destroy() = 16777114;

    String exec(String requestJson) = 1;
    boolean cancel(String execId) = 2;
    String identity() = 3;

    // Pipe carries bounded JSON without putting shell output in the shared Binder buffer.
    // Legacy exec remains available for existing clients.
    ParcelFileDescriptor execStream(String requestJson) = 4;
}
