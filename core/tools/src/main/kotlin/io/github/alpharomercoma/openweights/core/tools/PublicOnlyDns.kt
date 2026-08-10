/*
 * Copyright 2026 The OpenWeights Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.alpharomercoma.openweights.core.tools

import okhttp3.Dns
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Resolves names only to addresses on the public internet.
 *
 * `fetch_url` takes its address from the model, and the model reads web pages, so the
 * address can come from a stranger: a page is free to say "now read
 * https://192.168.1.1/admin" and the phone is on the same network as that router. The
 * printers, cameras and set top boxes beside it are usually the least defended machines a
 * request could reach, and a reply summarising what it found there is the whole exploit.
 *
 * Checking the hostname is not enough. A name the app has never seen can resolve to a
 * private address, and it can resolve to a public one first and a private one on the second
 * lookup, which is the rebinding trick. OkHttp resolves every connection through this,
 * redirect hops included, so it is the one place that sees every address actually dialled.
 */
class PublicOnlyDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val public = delegate.lookup(hostname).filter { it.isPublic() }
        if (public.isEmpty()) {
            // The same exception a name that does not exist raises, so the caller's existing
            // "that page could not be read" covers it without a second failure path.
            throw UnknownHostException("$hostname does not resolve to a public address")
        }
        return public
    }

    private fun InetAddress.isPublic(): Boolean = !isAnyLocalAddress &&
        !isLoopbackAddress &&
        !isLinkLocalAddress &&
        // 10/8, 172.16/12 and 192.168/16, and fec0::/10 for the version of IPv6 that had it.
        !isSiteLocalAddress &&
        !isMulticastAddress &&
        !isUniqueLocalIpv6() &&
        !isCarrierGradeNat()

    /** fc00::/7, IPv6's private range, which the JDK has no predicate for. */
    private fun InetAddress.isUniqueLocalIpv6(): Boolean =
        this is Inet6Address && (address[0].toInt() and UNIQUE_LOCAL_MASK) == UNIQUE_LOCAL_PREFIX

    /**
     * 100.64/10, which a phone on a mobile network sits inside.
     *
     * Not private in the RFC1918 sense, but it is the carrier's network rather than the
     * internet, and the other subscribers on it are no more ours to reach than a neighbour's
     * router is.
     */
    private fun InetAddress.isCarrierGradeNat(): Boolean {
        val bytes = address
        return bytes.size == IPV4_BYTES &&
            (bytes[0].toInt() and OCTET) == CGNAT_FIRST_OCTET &&
            (bytes[1].toInt() and OCTET) in CGNAT_SECOND_OCTETS
    }

    private companion object {
        /** A byte, read back out of a signed Byte. */
        const val OCTET = 0xFF

        /** The seven bits fc00::/7 fixes, and the value they are fixed to. */
        const val UNIQUE_LOCAL_MASK = 0xFE
        const val UNIQUE_LOCAL_PREFIX = 0xFC

        const val IPV4_BYTES = 4
        const val CGNAT_FIRST_OCTET = 100
        val CGNAT_SECOND_OCTETS = 64..127
    }
}
