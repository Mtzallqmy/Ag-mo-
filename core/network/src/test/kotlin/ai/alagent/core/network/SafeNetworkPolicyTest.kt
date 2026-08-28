package ai.alagent.core.network

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafeNetworkPolicyTest {
    @Test
    fun `allows public https destination`() {
        val policy = policyResolving("example.com" to arrayOf(ip("8.8.8.8")))

        assertTrue(policy.validate("https://example.com/path").allowed)
    }

    @Test
    fun `denies schemes credentials and local hostnames before connection`() {
        val resolver: (String) -> Array<InetAddress> = { arrayOf(ip("8.8.8.8")) }
        val policy = SafeNetworkPolicy(resolver = resolver)

        assertFalse(policy.validate("http://example.com").allowed)
        assertFalse(policy.validate("https://user:secret@example.com").allowed)
        assertFalse(policy.validate("https://localhost").allowed)
        assertFalse(policy.validate("https://service.local").allowed)
    }

    @Test
    fun `denies private and special use ipv4 ranges`() {
        val blocked = listOf(
            "0.1.2.3",
            "10.0.0.1",
            "100.64.0.1",
            "100.127.255.254",
            "127.0.0.1",
            "169.254.10.20",
            "172.16.0.1",
            "172.31.255.254",
            "192.0.0.1",
            "192.0.2.10",
            "192.88.99.1",
            "192.168.1.1",
            "198.18.0.1",
            "198.19.255.254",
            "198.51.100.20",
            "203.0.113.25",
            "224.0.0.1",
            "255.255.255.255"
        )

        blocked.forEach { address ->
            val policy = policyResolving("blocked.test" to arrayOf(ip(address)))
            assertFalse(policy.validate("https://blocked.test").allowed, address)
        }
    }

    @Test
    fun `denies unsafe ipv6 destinations and allows public ipv6`() {
        listOf("::", "::1", "fc00::1", "fd12:3456::1", "fe80::1", "2001:db8::1", "ff02::1").forEach { address ->
            val policy = policyResolving("v6.test" to arrayOf(ip(address)))
            assertFalse(policy.validate("https://v6.test").allowed, address)
        }

        val publicPolicy = policyResolving("v6.test" to arrayOf(ip("2606:4700:4700::1111")))
        assertTrue(publicPolicy.validate("https://v6.test").allowed)
    }

    @Test
    fun `denies ipv4 mapped ipv6 when embedded target is private`() {
        val bytes = ByteArray(16)
        bytes[10] = 0xff.toByte()
        bytes[11] = 0xff.toByte()
        bytes[12] = 10
        bytes[13] = 0
        bytes[14] = 0
        bytes[15] = 1
        val mapped = Inet6Address.getByAddress(null, bytes, -1)
        val policy = policyResolving("mapped.test" to arrayOf(mapped))

        assertFalse(policy.validate("https://mapped.test").allowed)
    }

    @Test
    fun `denies mixed dns answer when any address is unsafe`() {
        val policy = policyResolving("mixed.test" to arrayOf(ip("8.8.8.8"), ip("10.0.0.5")))

        assertFalse(policy.validate("https://mixed.test").allowed)
    }

    @Test
    fun `fails closed on dns error or empty result`() {
        val failing = SafeNetworkPolicy(resolver = { error("resolver unavailable") })
        val empty = SafeNetworkPolicy(resolver = { emptyArray() })

        assertFalse(failing.validate("https://example.com").allowed)
        assertFalse(empty.validate("https://example.com").allowed)
    }

    @Test
    fun `private network override only bypasses address classification`() {
        val policy = SafeNetworkPolicy(allowPrivateNetworks = true, resolver = { arrayOf(ip("10.0.0.2")) })

        assertTrue(policy.validate("https://example.com").allowed)
        assertFalse(policy.validate("https://localhost").allowed)
    }

    private fun policyResolving(entry: Pair<String, Array<InetAddress>>): SafeNetworkPolicy {
        val (host, addresses) = entry
        return SafeNetworkPolicy(resolver = { requested ->
            check(requested == host) { "Unexpected host: $requested" }
            addresses
        })
    }

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)
}
