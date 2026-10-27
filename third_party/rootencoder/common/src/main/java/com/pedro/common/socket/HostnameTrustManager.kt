package com.pedro.common.socket

import java.net.IDN
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * ScreenKit: require a matching SAN as well as a valid trust chain. Ktor 3.0.0's
 * verifier accepts certificates with no SAN of the requested type (notably IPs).
 */
internal class HostnameTrustManager(
    private val host: String,
    private val delegate: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first(),
) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = delegate.checkClientTrusted(chain, authType)
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        delegate.checkServerTrusted(chain, authType)
        val certificate = chain.firstOrNull() ?: throw CertificateException("Missing server certificate")
        val target = host.removePrefix("[").removeSuffix("]")
        val isIp = target.contains(':') || target.matches(Regex("[0-9.]+"))
        val matching = certificate.subjectAlternativeNames.orEmpty().any { san ->
            if (san.size < 2) false
            else if (isIp && san[0] == 7) {
                runCatching {
                    val address = when (val value = san[1]) {
                        is String -> InetAddress.getByName(value).address
                        is ByteArray -> value
                        else -> byteArrayOf()
                    }
                    InetAddress.getByName(target).address.contentEquals(address)
                }.getOrDefault(false)
            } else if (!isIp && san[0] == 2) {
                matchesDns(target, san[1] as? String ?: "")
            } else false
        }
        if (!matching) throw CertificateException("Server certificate does not match the destination hostname")
    }

    companion object {
        internal fun matchesDns(host: String, pattern: String): Boolean = runCatching {
            fun normalize(value: String) = IDN.toASCII(value.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            val target = normalize(host)
            if (!pattern.startsWith("*.")) target == normalize(pattern)
            else {
                val suffix = normalize(pattern.substring(2))
                // Only one complete leftmost label may be a wildcard, never *.com.
                suffix.contains('.') && target.endsWith(".$suffix") &&
                    target.removeSuffix(".$suffix").let { it.isNotEmpty() && !it.contains('.') }
            }
        }.getOrDefault(false)
    }
}
