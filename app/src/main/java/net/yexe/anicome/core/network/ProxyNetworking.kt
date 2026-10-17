package net.yexe.anicome

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.EOFException
import java.io.IOException
import java.net.*
import javax.net.SocketFactory

data class ProxyCredentials(val username: String = "", val password: String = "") {
    override fun toString() = "ProxyCredentials(redacted)"
    fun validate() { require(username.toByteArray().size <= 255 && password.toByteArray().size <= 255 && ':' !in username && '\r' !in username && '\n' !in username && '\r' !in password && '\n' !in password && (username.isNotEmpty() || password.isEmpty())) }
}

internal fun OkHttpClient.Builder.proxyConnection(options: AdvancedSettings): OkHttpClient.Builder {
    val credential = options.proxyCredentials
    credential.validate()
    proxyAuthenticator(Authenticator.NONE)
    return when (options.proxyType) {
        "http" -> proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(options.proxyHost.removeSurrounding("[", "]"), options.proxyPort)))
            .proxyAuthenticator { _, response ->
                if (credential.username.isEmpty() || response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(credential.username, credential.password)).build()
            }
        "socks" -> proxy(Proxy.NO_PROXY).socketFactory(AuthenticatedSocksFactory(options.proxyHost.removeSurrounding("[", "]"), options.proxyPort, credential))
            .dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0))) })
        else -> proxy(Proxy.NO_PROXY).dns(Dns.SYSTEM).socketFactory(SocketFactory.getDefault())
    }
}

internal class AuthenticatedSocksFactory(private val host: String, private val port: Int, private val credentials: ProxyCredentials) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val target = endpoint as? InetSocketAddress ?: throw IOException("Invalid SOCKS target")
            credentials.validate()
            super.connect(InetSocketAddress(this@AuthenticatedSocksFactory.host, this@AuthenticatedSocksFactory.port), timeout)
            val previousTimeout = soTimeout
            try {
                soTimeout = if (timeout > 0) timeout else 20_000
                val input = getInputStream(); val output = getOutputStream()
                fun read(): Int = input.read().also { if (it < 0) throw EOFException("SOCKS connection closed") }
                fun skip(length: Int) { repeat(length) { read() } }
                val authenticated = credentials.username.isNotEmpty()
                output.write(if (authenticated) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0)); output.flush()
                if (read() != 5) throw IOException("Invalid SOCKS version")
                when (read()) {
                    0 -> Unit
                    2 -> {
                        if (!authenticated) throw IOException("SOCKS authentication required")
                        val user = credentials.username.toByteArray(Charsets.UTF_8); val pass = credentials.password.toByteArray(Charsets.UTF_8)
                        output.write(byteArrayOf(1, user.size.toByte())); output.write(user); output.write(pass.size); output.write(pass); output.flush()
                        if (read() != 1 || read() != 0) throw IOException("SOCKS authentication failed")
                    }
                    else -> throw IOException("SOCKS authentication method rejected")
                }
                val name = target.hostString.removeSurrounding("[", "]")
                val address = if (':' in name) InetAddress.getByName(name).address else null
                output.write(byteArrayOf(5, 1, 0))
                if (address != null) { output.write(4); output.write(address) }
                else {
                    val domain = IDN.toASCII(name).toByteArray(Charsets.US_ASCII)
                    if (domain.size !in 1..255) throw IOException("Invalid SOCKS hostname")
                    output.write(3); output.write(domain.size); output.write(domain)
                }
                output.write(target.port ushr 8); output.write(target.port and 255); output.flush()
                if (read() != 5 || read() != 0 || read() != 0) throw IOException("SOCKS connection rejected")
                when (read()) { 1 -> skip(4); 3 -> skip(read()); 4 -> skip(16); else -> throw IOException("Invalid SOCKS reply") }
                skip(2)
            } catch (error: Exception) { close(); throw error }
            finally { if (!isClosed) soTimeout = previousTimeout }
        }
        override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)
    }
    private fun connect(address: InetSocketAddress, local: InetSocketAddress? = null): Socket = createSocket().apply { if (local != null) bind(local); connect(address) }
    override fun createSocket(host: String, port: Int) = connect(InetSocketAddress.createUnresolved(host, port))
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = connect(InetSocketAddress.createUnresolved(host, port), InetSocketAddress(local, localPort))
    override fun createSocket(address: InetAddress, port: Int) = connect(InetSocketAddress(address, port))
    override fun createSocket(address: InetAddress, port: Int, local: InetAddress, localPort: Int) = connect(InetSocketAddress(address, port), InetSocketAddress(local, localPort))
}
