package ai.alagent.core.network

import java.net.InetAddress
import java.net.URI

/**
 * Default-deny outbound URL validation designed to reduce SSRF and accidental LAN access.
 *
 * The policy rejects private, loopback, link-local, multicast and common special-use address
 * ranges after DNS resolution. Callers that execute a request should still use a transport that
 * does not silently re-resolve to a different address between validation and connection.
 */
class SafeNetworkPolicy(
    private val allowedSchemes: Set<String> = setOf("https"),
    private val allowPrivateNetworks: Boolean = false,
    private val resolver: (String) -> Array<InetAddress> = InetAddress::getAllByName
) {
    fun validate(rawUrl: String): NetworkPolicyResult {
        val uri = runCatching { URI(rawUrl) }.getOrElse { return NetworkPolicyResult(false, "Invalid URL") }
        val scheme = uri.scheme?.lowercase() ?: return NetworkPolicyResult(false, "URL scheme is required")
        if (scheme !in allowedSchemes) return NetworkPolicyResult(false, "URL scheme is not allowed")
        if (uri.userInfo != null) return NetworkPolicyResult(false, "Credentials in URL authority are not allowed")
        val host = uri.host?.lowercase() ?: return NetworkPolicyResult(false, "URL host is required")
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
            return NetworkPolicyResult(false, "Local hostnames are blocked")
        }
        if (!allowPrivateNetworks) {
            val addresses = runCatching { resolver(host).toList() }
                .getOrElse { return NetworkPolicyResult(false, "DNS resolution failed") }
            if (addresses.isEmpty()) return NetworkPolicyResult(false, "Host did not resolve")
            if (addresses.any(::isPrivateOrSpecial)) {
                return NetworkPolicyResult(false, "Private, loopback, link-local, or special network targets are blocked")
            }
        }
        return NetworkPolicyResult(true)
    }

    private fun isPrivateOrSpecial(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return true

        val bytes = address.address
        return when (bytes.size) {
            4 -> isSpecialIpv4(bytes)
            16 -> isSpecialIpv6(bytes)
            else -> true
        }
    }

    private fun isSpecialIpv4(bytes: ByteArray): Boolean {
        val a = bytes[0].u8()
        val b = bytes[1].u8()
        val c = bytes[2].u8()

        if (a == 0 || a == 10 || a == 127) return true
        if (a == 100 && b in 64..127) return true // RFC 6598 shared address space
        if (a == 169 && b == 254) return true
        if (a == 172 && b in 16..31) return true
        if (a == 192 && b == 0 && c == 0) return true // IETF protocol assignments
        if (a == 192 && b == 0 && c == 2) return true // TEST-NET-1
        if (a == 192 && b == 88 && c == 99) return true // deprecated 6to4 relay anycast
        if (a == 192 && b == 168) return true
        if (a == 198 && b in 18..19) return true // benchmark testing
        if (a == 198 && b == 51 && c == 100) return true // TEST-NET-2
        if (a == 203 && b == 0 && c == 113) return true // TEST-NET-3
        if (a >= 224) return true // multicast, reserved and limited broadcast
        return false
    }

    private fun isSpecialIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].u8()
        if ((first and 0xfe) == 0xfc) return true // fc00::/7 unique local
        if (first == 0xff) return true // multicast

        // 2001:db8::/32 documentation prefix.
        if (bytes[0].u8() == 0x20 && bytes[1].u8() == 0x01 && bytes[2].u8() == 0x0d && bytes[3].u8() == 0xb8) {
            return true
        }

        // IPv4-mapped (::ffff:a.b.c.d) and deprecated IPv4-compatible (::a.b.c.d)
        // forms inherit the IPv4 destination's classification.
        val firstTenZero = bytes.take(10).all { it.toInt() == 0 }
        val firstTwelveZero = bytes.take(12).all { it.toInt() == 0 }
        val mapped = firstTenZero && bytes[10].u8() == 0xff && bytes[11].u8() == 0xff
        if (mapped || firstTwelveZero) return isSpecialIpv4(bytes.copyOfRange(12, 16))

        return false
    }

    private fun Byte.u8(): Int = toInt() and 0xff
}

data class NetworkPolicyResult(val allowed: Boolean, val reason: String? = null)
