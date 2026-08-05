package com.catchzoon.network.core

/**
 * 平台传输安全策略。
 *
 * 证书固定使用完整 DER 证书的 SHA-256 十六进制值，格式为 `cert-sha256/<64位小写hex>`；
 * 同一主机至少保留当前和下一张证书两个 Pin，避免证书轮换导致客户端永久断网。
 */
public data class NetworkTlsPolicy(
    val allowCleartext: Boolean = false,
    val certificatePins: Map<String, Set<String>> = emptyMap(),
) {
    init {
        require(certificatePins.size <= MAX_PINNED_HOSTS) { "证书固定主机数量不能超过 $MAX_PINNED_HOSTS" }
        certificatePins.forEach { (host, pins) ->
            require(host.isNotBlank() && host == host.lowercase() && '/' !in host && ':' !in host) {
                "证书固定只支持小写主机名"
            }
            require(pins.size in 2..MAX_PINS_PER_HOST && pins.all(PIN_PATTERN::matches)) {
                "每个主机必须配置 2..$MAX_PINS_PER_HOST 个合法证书 Pin"
            }
        }
    }

    internal fun pinsForHost(host: String): Set<String> = certificatePins[host.lowercase()].orEmpty()

    internal fun validateBaseUrl(baseUrl: String) {
        require(allowCleartext || baseUrl.startsWith("https://", ignoreCase = true)) {
            "生产网络客户端只允许 HTTPS"
        }
    }
}

/** 把 SHA-256 十六进制摘要转换为证书 Pin。 */
public fun certificateSha256Pin(hexDigest: String): String = "cert-sha256/${hexDigest.lowercase()}".also {
    require(PIN_PATTERN.matches(it)) { "证书 SHA-256 必须是 64 位十六进制字符串" }
}

private val PIN_PATTERN = Regex("cert-sha256/[0-9a-f]{64}")
private const val MAX_PINNED_HOSTS = 16
private const val MAX_PINS_PER_HOST = 8
