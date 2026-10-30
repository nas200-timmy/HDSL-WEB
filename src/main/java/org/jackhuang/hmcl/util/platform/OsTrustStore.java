/*
 * HDSL-web
 * Copyright (C) 2026  HDSL-web contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.util.platform;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.security.KeyStore;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Makes the JVM trust what Windows trusts, on Windows.
///
/// The browser can reach a site the panel cannot, and the reason is almost never
/// the network: a TLS-inspecting proxy (an antivirus, a corporate gateway, a
/// GitHub accelerator such as FastGithub) installs its own root into the
/// **Windows** store, and Java never looks there. Its `cacerts` has the public
/// CAs and nothing else, so every request to an intercepted host fails with
/// `PKIX path building failed` — API listings come back empty, a source tarball
/// "could not be downloaded", and nothing says why.
///
/// The JVM can use that store: `javax.net.ssl.trustStoreType=Windows-ROOT` makes
/// it read the machine's own roots through the `SunMSCAPI` provider. The property
/// has to be set before the first `SSLContext` is created, which is why this runs
/// at the very top of startup.
///
/// Three limits, all deliberate:
///
/// - **Windows only.** Elsewhere the system store is not a JDK concept.
/// - **An operator's setting wins.** Anyone who named a trust store (or a type)
///   on the command line meant it; this never overrides that.
/// - **Only when the store is readable.** A JDK without the `SunMSCAPI` module
///   cannot read `Windows-ROOT`, and switching to something unreadable would
///   break every TLS connection rather than fix the intercepted ones.
@NotNullByDefault
public final class OsTrustStore {

    /// The `javax.net.ssl.trustStoreType` value that means "the machine's roots".
    public static final String WINDOWS_ROOTS = "Windows-ROOT";

    private OsTrustStore() {
    }

    /// Switches the JVM to the Windows root store when that is the right answer on
    /// this machine.
    ///
    /// @return whether the property was set here
    public static boolean prefer() {
        boolean loadable = WINDOWS_ROOTS.equals(systemProperty("javax.net.ssl.trustStoreType")) || readable();
        if (!shouldSwitch(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
                systemProperty("javax.net.ssl.trustStore"),
                systemProperty("javax.net.ssl.trustStoreType"),
                loadable)) {
            return false;
        }
        System.setProperty("javax.net.ssl.trustStoreType", WINDOWS_ROOTS);
        LOG.info("TLS: using the Windows root store (javax.net.ssl.trustStoreType=" + WINDOWS_ROOTS
                + "), so a proxy's own root is trusted the way the browser trusts it");
        return true;
    }

    /// Whether the JVM should be pointed at the Windows root store.
    ///
    /// Package-private and parameterised so the decision can be read on its own,
    /// without a Windows machine and without changing this JVM's TLS.
    ///
    /// @param windows        whether the platform can read that store
    /// @param trustStore     the operator's `javax.net.ssl.trustStore`, or `null`
    /// @param trustStoreType the operator's `javax.net.ssl.trustStoreType`, or `null`
    /// @param readable       whether the store could actually be opened
    /// @return whether to set the property
    static boolean shouldSwitch(boolean windows, @Nullable String trustStore,
                                @Nullable String trustStoreType, boolean readable) {
        if (!windows || !readable) {
            return false;
        }
        if (trustStore != null && !trustStore.isBlank()) {
            return false;
        }
        if (trustStoreType != null && !trustStoreType.isBlank()) {
            return false;
        }
        return true;
    }

    /// Whether `Windows-ROOT` can be opened by this JVM.
    private static boolean readable() {
        try {
            KeyStore store = KeyStore.getInstance(WINDOWS_ROOTS);
            store.load(null, null);
            return true;
        } catch (Exception | LinkageError e) {
            // A JDK without SunMSCAPI (or a running configuration that denies it)
            // says so here; the panel keeps the default trust store.
            return false;
        }
    }

    private static @Nullable String systemProperty(String name) {
        try {
            return System.getProperty(name);
        } catch (SecurityException e) {
            return null;
        }
    }
}
