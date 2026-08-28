package ai.alagent.core.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/** Default-deny outbound URL validation designed to reduce SSRF and accidental LAN access. */
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
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return NetworkPolicyResult(false, "Local hostnames are blocked")
        if (!allowPrivateNetworks) {
            val addresses = runCatching { resolver(host).toList() }.getOrElse { return NetworkPolicyResult(false, "DNS resolution failed") }
            if (addresses.isEmpty()) return NetworkPolicyResult(false, "Host did not resolve")
            if (addresses.any(::isPrivateOrSpecial)) return NetworkPolicyResult(false, "Private, loopback, link-local, or special network targets are blocked")
        }
        return NetworkPolicyResult(true)
    }

    private fun isPrivateOrSpecial(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address
        if (address is Inet4Address && bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            if (a == 0 || a == 10 || a == 127) return true
            if (a == 169 && b == 254) return true
            if (a == 172 && b in 16..31) return true
            if (a == 192 && b == 168) return true
            if (a >= 224) return true
        }
        if (address is Inet6Address && bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            if ((first and 0xfe) == 0xfc) return true // fc00::/7 unique local
            if (first == 0xff) return true // multicast
        }
        return false
    }
}

data class NetworkPolicyResult(val allowed: Boolean, val reason: String? = null)
