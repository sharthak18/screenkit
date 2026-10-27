package com.pedro.common

import com.pedro.common.socket.TcpStreamSocketImp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** Real local TLS handshakes. The bundled test certificate is not trusted by production code. */
class TlsSocketTest {
  private fun withServer(block: (Int, X509TrustManager) -> Unit) {
    val store = KeyStore.getInstance("PKCS12").apply {
      TlsSocketTest::class.java.getResourceAsStream("/localhost-test-only.p12").use { load(it, "test-only".toCharArray()) }
    }
    val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
      init(store, "test-only".toCharArray())
    }
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
    val context = SSLContext.getInstance("TLSv1.2").apply { init(keys.keyManagers, trust.trustManagers, null) }
    val server = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
    server.enabledProtocols = arrayOf("TLSv1.2")
    val worker = thread(isDaemon = true) {
      runCatching { (server.accept() as SSLSocket).use { socket -> socket.startHandshake(); socket.inputStream.read() } }
    }
    try { block(server.localPort, trust.trustManagers.filterIsInstance<X509TrustManager>().first()) }
    finally { server.close(); worker.join(1000) }
  }

  @Test(timeout = 15000) fun acceptsTrustedMatchingHostname() = withServer { port, trust ->
    runBlocking {
      val socket = TcpStreamSocketImp("localhost", port, true, trust)
      try { withTimeout(7000) { socket.connect() }; assertTrue("TLS socket should be connected", socket.isConnected()) }
      finally { socket.close() }
      assertFalse("Closed socket must not report connected", socket.isConnected())
    }
  }

  @Test(timeout = 15000) fun rejectsTrustedCertificateForWrongHostname() = withServer { port, trust ->
    runBlocking {
      val socket = TcpStreamSocketImp("127.0.0.1", port, true, trust)
      try { assertTrue(runCatching { withTimeout(7000) { socket.connect() } }.isFailure) }
      finally { socket.close() }
    }
  }

  @Test(timeout = 15000) fun rejectsUntrustedCertificate() = withServer { port, _ ->
    runBlocking {
      val socket = TcpStreamSocketImp("localhost", port, true, null)
      try { assertTrue(runCatching { withTimeout(7000) { socket.connect() } }.isFailure) }
      finally { socket.close() }
    }
  }
}
