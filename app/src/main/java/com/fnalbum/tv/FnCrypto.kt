package com.fnalbum.tv

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 飞牛相册加密与签名。
 *
 * 签名公式（已用真实服务器回包逐字节验证）：
 *   paramStr  = GET -> 按 key 升序的 "k=v"，用 '&' 连接，值为原始值
 *               POST -> JSON body 原文
 *   paramHash = MD5(paramStr)
 *   sign      = MD5(salt_path_nonce_timestamp_paramHash_secret)
 *   authx     = "nonce=..&timestamp=..&sign=.."
 * 注意：签名 path 必须带 /p 前缀；secret 为固定值，与登录返回的 secret 无关。
 */
object FnCrypto {

    const val SALT = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh"
    const val SIGN_SECRET = "EAECCF25-80A6-4666-A7C2-A76904A74AB6"

    private const val HEX_LOWER = "0123456789abcdef"
    private const val HEX_UPPER = "0123456789ABCDEF"

    /** 与前端一致的 did 字符表 */
    const val DID_CHARS = "useandom-26T198340PX75pxJACKVERYMINDBUSHWOLF_GQZbfghjklqvwyzrict"
    private const val AES_KEY_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    private val random = SecureRandom()

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_LOWER[v ushr 4]).append(HEX_LOWER[v and 0x0F])
        }
        return sb.toString()
    }

    /** 生成 authx 头。path 必须已是带 /p 前缀的完整路径。 */
    fun buildAuthx(method: String, path: String, params: Map<String, String>?, body: String?): String {
        val isGet = method.equals("GET", ignoreCase = true)
        val paramStr = if (isGet) {
            (params ?: emptyMap())
                .filterValues { it.isNotEmpty() }
                .toSortedMap()
                .entries
                .joinToString("&") { "${it.key}=${it.value}" }
        } else {
            body ?: ""
        }
        val paramHash = md5Hex(paramStr)
        val nonce = (100000 + random.nextInt(900000)).toString()
        val timestamp = System.currentTimeMillis().toString()
        val sign = md5Hex(listOf(SALT, path, nonce, timestamp, paramHash, SIGN_SECRET).joinToString("_"))
        return "nonce=$nonce&timestamp=$timestamp&sign=$sign"
    }

    /**
     * 按前端 URLSearchParams 的规则编码查询串：
     * 空格 -> '+'，A-Za-z0-9*-._ 原样，其余按 UTF-8 百分号编码（大写十六进制）。
     */
    fun encodeQuery(params: Map<String, String>): String {
        if (params.isEmpty()) return ""
        return params.entries.joinToString("&") { "${urlEnc(it.key)}=${urlEnc(it.value)}" }
    }

    private fun urlEnc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            when {
                c == 0x20 -> sb.append('+')
                c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A -> sb.append(c.toChar())
                c == 0x2A || c == 0x2D || c == 0x2E || c == 0x5F -> sb.append(c.toChar())
                else -> sb.append('%').append(HEX_UPPER[c ushr 4]).append(HEX_UPPER[c and 0x0F])
            }
        }
        return sb.toString()
    }

    fun randomString(length: Int, alphabet: String): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(alphabet[random.nextInt(alphabet.length)]) }
        return sb.toString()
    }

    /**
     * 用 RSA 公钥（PKCS#1 PEM）加密 AES 密钥，再用 AES-256-CBC 加密登录 payload。
     * @return Triple(ivBase64, rsaBase64, aesBase64)
     */
    fun encryptLogin(payloadJson: String, publicKeyPem: String): Triple<String, String, String> {
        val aesKeyStr = randomString(32, AES_KEY_CHARS)
        val keySpec = SecretKeySpec(aesKeyStr.toByteArray(Charsets.UTF_8), "AES")

        val iv = ByteArray(16).also { random.nextBytes(it) }
        val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aes.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
        val aesB64 = Base64.encodeToString(
            aes.doFinal(payloadJson.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
        )

        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, parsePublicKey(publicKeyPem))
        val rsaB64 = Base64.encodeToString(
            rsa.doFinal(aesKeyStr.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
        )

        return Triple(Base64.encodeToString(iv, Base64.NO_WRAP), rsaB64, aesB64)
    }

    /**
     * 兼容两种常见 PEM：
     *  - "BEGIN PUBLIC KEY"     -> X.509 SubjectPublicKeyInfo（飞牛服务器实际返回的格式）
     *  - "BEGIN RSA PUBLIC KEY" -> PKCS#1 RSAPublicKey
     */
    private fun parsePublicKey(pem: String): RSAPublicKey {
        val body = pem.replace(Regex("-----[^-]*-----"), "").replace(Regex("\\s"), "")
        val der = Base64.decode(body, Base64.DEFAULT)
        return try {
            KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
        } catch (t: Throwable) {
            parsePkcs1(der)
        }
    }

    /**
     * PKCS#1 ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }
     */
    private fun parsePkcs1(der: ByteArray): RSAPublicKey {
        var pos = 0

        require(der[pos].toInt() and 0xFF == 0x30) { "RSA 公钥格式错误：缺少 SEQUENCE" }
        pos++
        pos += readLength(der, pos).second

        require(der[pos].toInt() and 0xFF == 0x02) { "RSA 公钥格式错误：缺少 modulus" }
        pos++
        val (nLen, nLenSize) = readLength(der, pos)
        pos += nLenSize
        val modulus = BigInteger(1, der.copyOfRange(pos, pos + nLen))
        pos += nLen

        require(der[pos].toInt() and 0xFF == 0x02) { "RSA 公钥格式错误：缺少 exponent" }
        pos++
        val (eLen, eLenSize) = readLength(der, pos)
        pos += eLenSize
        val exponent = BigInteger(1, der.copyOfRange(pos, pos + eLen))

        val spec = RSAPublicKeySpec(modulus, exponent)
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    /** @return Pair(长度, 长度字段占用的字节数) */
    private fun readLength(der: ByteArray, offset: Int): Pair<Int, Int> {
        val first = der[offset].toInt() and 0xFF
        if (first and 0x80 == 0) return first to 1
        val n = first and 0x7F
        var value = 0
        for (i in 1..n) value = (value shl 8) or (der[offset + i].toInt() and 0xFF)
        return value to (1 + n)
    }
}
