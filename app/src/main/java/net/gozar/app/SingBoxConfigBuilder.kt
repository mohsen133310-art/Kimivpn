package net.gozar.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds sing-box JSON from the app's existing ProxyConfig model.
 *
 * This is the first migration layer. The VPN service must call Libbox/CommandServer
 * with this configuration before the legacy Gozarcore engine can be removed.
 */
object SingBoxConfigBuilder {


    fun buildDirectOnly(): String {
        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("inet4_address", JSONArray().put("172.19.0.1/30"))
            .put("mtu", 1500)
            .put("auto_route", true)
            .put("strict_route", true)
            .put("stack", "mixed")
        val direct = JSONObject().put("type", "direct").put("tag", "direct")
        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", JSONObject().put("servers", JSONArray().put(JSONObject().put("tag", "dns-direct").put("address", "local"))).put("final", "dns-direct"))
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", JSONArray().put(direct))
            .put("route", JSONObject().put("auto_detect_interface", true).put("final", "direct"))
            .toString()
    }

    fun build(config: ProxyConfig): String {
        require(config.address.isNotBlank()) { "Server address is missing" }
        require(config.port in 1..65535) { "Server port is invalid" }

        val outbound = when (config.protocol.lowercase()) {
            "vless" -> vless(config)
            "vmess" -> vmess(config)
            "trojan" -> trojan(config)
            else -> throw IllegalArgumentException("Unsupported sing-box protocol: ${config.protocol}")
        }

        val dns = JSONObject()
            .put("servers", JSONArray()
                .put(JSONObject().put("tag", "dns-remote").put("address", "https://1.1.1.1/dns-query").put("detour", "proxy"))
                .put(JSONObject().put("tag", "dns-direct").put("address", "local")))
            .put("final", "dns-remote")

        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("inet4_address", JSONArray().put("172.19.0.1/30"))
            .put("mtu", if (config.mtu in 576..9000) config.mtu else 1500)
            .put("auto_route", true)
            .put("strict_route", true)
            .put("stack", "mixed")

        val direct = JSONObject().put("type", "direct").put("tag", "direct")
        val block = JSONObject().put("type", "block").put("tag", "block")

        val route = JSONObject()
            .put("auto_detect_interface", true)
            .put("final", "proxy")
            .put("rules", JSONArray()
                .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns")))

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", JSONArray().put(outbound).put(direct).put(block))
            .put("route", route)
            .toString()
    }

    private fun vless(c: ProxyConfig) = JSONObject()
        .put("type", "vless")
        .put("tag", "proxy")
        .put("server", c.address)
        .put("server_port", c.port)
        .put("uuid", c.uuid)
        .put("flow", c.flow)
        .apply { addTransport(c); addTls(c) }

    private fun vmess(c: ProxyConfig) = JSONObject()
        .put("type", "vmess")
        .put("tag", "proxy")
        .put("server", c.address)
        .put("server_port", c.port)
        .put("uuid", c.uuid)
        .put("alter_id", c.alterId)
        .put("security", c.encryption.ifBlank { "auto" })
        .apply { addTransport(c); addTls(c) }

    private fun trojan(c: ProxyConfig) = JSONObject()
        .put("type", "trojan")
        .put("tag", "proxy")
        .put("server", c.address)
        .put("server_port", c.port)
        .put("password", c.password)
        .apply { addTransport(c); addTls(c) }

    private fun JSONObject.addTransport(c: ProxyConfig) {
        when (c.network.lowercase()) {
            "ws", "websocket" -> put("transport", JSONObject()
                .put("type", "ws")
                .put("path", c.path.ifBlank { "/" })
                .put("headers", JSONObject().put("Host", c.host.ifBlank { c.sni })))
            "grpc" -> put("transport", JSONObject()
                .put("type", "grpc")
                .put("service_name", c.serviceName.ifBlank { c.path }))
            "http", "h2" -> put("transport", JSONObject()
                .put("type", "http")
                .put("host", JSONArray().put(c.host.ifBlank { c.sni }))
                .put("path", c.path.ifBlank { "/" }))
            "httpupgrade" -> put("transport", JSONObject()
                .put("type", "httpupgrade")
                .put("host", c.host.ifBlank { c.sni })
                .put("path", c.path.ifBlank { "/" }))
            // sing-box uses the protocol's native stream when no transport is set.
            "tcp", "" -> Unit
            else -> throw IllegalArgumentException("Unsupported transport: ${c.network}")
        }
    }

    private fun JSONObject.addTls(c: ProxyConfig) {
        val security = c.security.lowercase()
        if (security != "tls" && security != "reality") return

        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", c.sni.ifBlank { c.host.ifBlank { c.address } })
            .put("insecure", c.allowInsecure)

        if (c.alpn.isNotBlank()) {
            tls.put("alpn", JSONArray().apply {
                c.alpn.split(',').map(String::trim).filter(String::isNotEmpty).forEach(::put)
            })
        }

        if (c.fingerprint.isNotBlank()) {
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", c.fingerprint))
        }

        if (security == "reality") {
            require(c.publicKey.isNotBlank()) { "REALITY public key (pbk) is missing" }
            tls.put("reality", JSONObject()
                .put("enabled", true)
                .put("public_key", c.publicKey)
                .put("short_id", c.shortId))
        }
        put("tls", tls)
    }
}
