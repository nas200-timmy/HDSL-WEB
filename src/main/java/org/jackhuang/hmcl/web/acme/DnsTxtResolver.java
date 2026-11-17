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

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.time.Duration;
import java.util.Hashtable;

/// Waits for the ACME TXT record to become visible through the system DNS
/// resolver. DNS propagation is the most common DNS-01 failure; a fixed sleep
/// is not enough, and the CA's validation will fail if the record is not there.
@NotNullByDefault
public final class DnsTxtResolver {

    private static final Duration POLL = Duration.ofSeconds(5);

    /// @return true when a TXT value equal to `expected` is visible before the
    ///         timeout; false otherwise
    public boolean await(String fqdn, String expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (visible(fqdn, expected)) {
                return true;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            try {
                Thread.sleep(Math.min(POLL.toMillis(), Math.max(1, remaining / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

    private boolean visible(String fqdn, String expected) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(javax.naming.Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        InitialDirContext context = null;
        try {
            context = new InitialDirContext(env);
            Attributes attributes = context.getAttributes(fqdn, new String[]{"TXT"});
            Attribute txt = attributes.get("TXT");
            if (txt == null) {
                return false;
            }
            for (int i = 0; i < txt.size(); i++) {
                Object value = txt.get(i);
                if (value != null && normalize(value.toString()).equals(expected)) {
                    return true;
                }
            }
            return false;
        } catch (NamingException e) {
            return false;
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (NamingException ignored) {
                    // Nothing useful to do.
                }
            }
        }
    }

    /// DNS TXT answers may be split into quoted chunks (`"abc" "def"`); remove
    /// the quotes and concatenate them. A single chunk is the common case.
    static String normalize(String value) {
        StringBuilder builder = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && Character.isWhitespace(c)) {
                continue;
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}
