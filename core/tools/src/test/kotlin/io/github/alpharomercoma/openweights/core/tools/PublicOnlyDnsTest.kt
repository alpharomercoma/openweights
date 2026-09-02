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

import com.google.common.truth.Truth.assertThat
import okhttp3.Dns
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The resolver that keeps `fetch_url` on the public internet.
 *
 * Worth a test of its own because the address it is defending against comes from a model,
 * which read it off a web page, which anyone can write. The delegate is a fake so the test
 * asserts on the rule rather than on whatever DNS answers today.
 */
class PublicOnlyDnsTest {
    private fun resolvingTo(vararg addresses: String) = Dns { _ ->
        addresses.map { InetAddress.getByName(it) }
    }

    @Test
    fun `a public address is returned`() {
        val resolved = PublicOnlyDns(resolvingTo("93.184.216.34")).lookup("example.com")

        assertThat(resolved.map { it.hostAddress }).containsExactly("93.184.216.34")
    }

    @Test
    fun `the addresses worth reaching a router on are refused`() {
        val refused = listOf(
            "127.0.0.1" to "loopback",
            "10.0.0.1" to "private class A",
            "172.16.0.1" to "private class B",
            "192.168.1.1" to "the home router",
            "169.254.1.1" to "link local",
            "100.64.0.1" to "carrier grade NAT",
            "::1" to "IPv6 loopback",
            "fd00::1" to "IPv6 unique local",
        )

        refused.forEach { (address, what) ->
            val dns = PublicOnlyDns(resolvingTo(address))

            val failure = runCatching { dns.lookup("wherever.example") }.exceptionOrNull()

            assertThat(failure).isInstanceOf(UnknownHostException::class.java)
            assertThat(what).isNotEmpty()
        }
    }

    @Test
    fun `a host written as digits is private when it names anything but the internet`() {
        // The check for callers with only the resolver boundary, which never sees these.
        assertThat("192.168.1.1".isPrivateLiteral()).isTrue()
        assertThat("127.0.0.1".isPrivateLiteral()).isTrue()
        assertThat("169.254.169.254".isPrivateLiteral()).isTrue()
        assertThat("[::1]".isPrivateLiteral() || "::1".isPrivateLiteral()).isTrue()
        assertThat("93.184.216.34".isPrivateLiteral()).isFalse()
        // A name is the resolver's question, never a private literal.
        assertThat("router.local".isPrivateLiteral()).isFalse()
    }

    @Test
    fun `a name that answers with both keeps only the public address`() {
        // The shape of a rebinding attempt: one address to pass a check, one to connect to.
        val dns = PublicOnlyDns(resolvingTo("192.168.0.5", "93.184.216.34"))

        val resolved = dns.lookup("rebound.example")

        assertThat(resolved.map { it.hostAddress }).containsExactly("93.184.216.34")
    }
}
