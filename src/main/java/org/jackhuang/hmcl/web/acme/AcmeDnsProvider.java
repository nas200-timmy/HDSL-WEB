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
package org.jackhuang.hmcl.web.acme;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Map;

/// One DNS provider's write side, as ACME DNS-01 needs it: put a TXT record
/// under `_acme-challenge.<name>`, then remove it after validation.
///
/// Implementations receive a copy of the provider-specific credential map.
/// They must never log it, put it in an exception message, or keep it after
/// the call.
@NotNullByDefault
public interface AcmeDnsProvider {

    /// The registry id (`cloudflare`, `aliyun`, `dnspod`, ...).
    String id();

    /// Creates (or replaces) the TXT record. The implementation may keep the
    /// provider's record id in memory so [cleanup] can delete exactly that
    /// record; a fresh process can also look it up by name/value.
    void present(String fqdn, String value) throws AcmeDnsException;

    /// Removes the TXT record created by [present]. Best-effort: a missing
    /// record is not an error.
    void cleanup(String fqdn, String value) throws AcmeDnsException;

    /// Read-only check that the credential is well-formed and permitted.
    ///
    /// Used by the settings page's "test credential" button, before a
    /// certificate is issued. Providers call the cheapest list/verify endpoint
    /// they have; a least-privilege key that may edit records but not list
    /// domains can still fail here, so the message should say which permission
    /// the check needed.
    default void test() throws AcmeDnsException {
        throw new AcmeDnsException("this DNS provider does not support credential testing");
    }
}
