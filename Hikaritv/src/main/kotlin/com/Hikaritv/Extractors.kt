package com.hikaritv

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.math.BigInteger
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Ghbrisk : Filesim() {
    override val name = "Streamwish"
    override val mainUrl = "https://ghbrisk.com"
}

class Swishsrv : Filesim() {
    override val name = "Streamwish"
    override val mainUrl = "https://swishsrv.com"
}


class Boosterx : Chillx() {
    override val name = "Boosterx"
    override val mainUrl = "https://boosterx.stream"
}

// Why are so mad at us Cracking it
open class Chillx : ExtractorApi() {
    override val name = "Chillx"
    override val mainUrl = "https://chillx.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseurl=getBaseUrl(url)
        val headers = mapOf(
            "Origin" to baseurl,
            "Referer" to baseurl,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"
        )
        // Diffie-Hellman parameters
        val dhModulus = BigInteger("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA237327FFFFFFFFFFFFFFFF", 16)
        val generator = BigInteger("2")
        val keyBytes = 32 // 256-bit key
        val randomBytes = ByteArray(keyBytes)
        SecureRandom().nextBytes(randomBytes)
        val clientPrivateKey = BigInteger(1, randomBytes).mod(dhModulus)
        val clientPublicKey = generator.modPow(clientPrivateKey, dhModulus)

        // Request for token
        val nonce = getNonce()

        try {
            val res = app.get(url, referer = referer ?: mainUrl, headers = headers).toString()

            // Extract encoded string from response
            val encodedString = Regex("(?:const|let|var|window\\.\\w+)\\s+\\w*\\s*=\\s*'(.*?)'").find(res)
                ?.groupValues?.get(1)?.trim() ?: ""
            if (encodedString.isEmpty()) {
                throw Exception("Encoded string not found")
            }

            val postDataStep2 = """{
                "nonce": "$nonce",
                "client_public": "$clientPublicKey"
            }""".toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

            val step2Response = app.post("$baseurl/api-2/prepair-token.php", requestBody = postDataStep2, headers = headers).toString()
            val jsonResponse2 = Gson().fromJson(step2Response, JsonObject::class.java)
            val preToken = jsonResponse2.get("pre_token")?.asString ?: ""
            val csrfToken = jsonResponse2.get("csrf_token")?.asString ?: ""
            val serverPublicKey = jsonResponse2.get("server_public")?.asString ?: ""

            // Step 3: Create token
            val postDataStep3 = """{
                "nonce": "$nonce",
                "pre_token": "$preToken",
                "csrf_token": "$csrfToken"
            }""".toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

            val step3Response = app.post("$baseurl/api-2/create-token.php", requestBody = postDataStep3, headers = headers).toString()
            val sessionToken = Gson().fromJson(step3Response, JsonObject::class.java).get("token")?.asString ?: ""

            // Step 4: Process final request
            val postDataLast = """{
                "token": "$sessionToken",
                "nonce": "$nonce",
                "initial_nonce": "$nonce",
                "pre_token": "$preToken",
                "csrf_token": "$csrfToken",
                "encrypted_data": "$encodedString"
            }""".toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

            val stepLastResponse = app.post("$baseurl/api-2/last-process.php", requestBody = postDataLast, headers = headers).toString()
            val decryptedData = decodeData(stepLastResponse,serverPublicKey,clientPrivateKey,dhModulus).toString()

            // Extract m3u8 URL
            val m3u8 = Regex("(https?://[^\\s\"'\\\\]*m3u8[^\\s\"'\\\\]*)").find(decryptedData)
                ?.groupValues?.get(1)?.trim() ?: ""
            if (m3u8.isEmpty()) {
                throw Exception("m3u8 URL not found")
            }

            // Prepare headers for callback
            val header = mapOf(
                "accept" to "*/*",
                "accept-language" to "en-US,en;q=0.5",
                "Origin" to mainUrl,
                "Accept-Encoding" to "gzip, deflate, br",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "user-agent" to USER_AGENT
            )

            // Return the extractor link
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    url = m3u8,
                    INFER_TYPE
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.P1080.value
                    this.headers = header
                }
            )

            // Extract and return subtitles
            val subtitles = extractSrtSubtitles(decryptedData)
            subtitles.forEachIndexed { _, (language, url) ->
                subtitleCallback.invoke(SubtitleFile(language, url))
            }

        } catch (e: Exception) {
            Log.e("Anisaga Stream", "Error: ${e.message}")
        }
    }

    private fun decodeData(
        response: String,
        serverPublicKey: String,
        clientPrivateKey: BigInteger,
        dhModulus: BigInteger
    ): String? {
        val res = Gson().fromJson(response, JsonObject::class.java)
        val tempiv = res.get("temp_iv")?.asString ?: return null
        val encryptedSymmetricKeyStr = res.get("encrypted_symmetric_key")?.asString ?: return null
        val ivStr = res.get("iv")?.asString ?: return null
        val encryptedResultStr = res.get("encrypted_result")?.asString ?: return null

        val shared = modExp(
            BigInteger(serverPublicKey),
            clientPrivateKey,
            dhModulus
        )

        val derivedKey = MessageDigest.getInstance("SHA-256").digest(shared.toString().toByteArray())

// Decrypt symmetric key
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKey = SecretKeySpec(derivedKey, "AES")
        val ivSpec = IvParameterSpec(base64DecodeArray(tempiv))
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        val encryptedSymmetricKey = base64DecodeArray(encryptedSymmetricKeyStr)
        val decryptedBytes = cipher.doFinal(encryptedSymmetricKey)

// Decrypt final data
        val aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val aesKey = SecretKeySpec(decryptedBytes, "AES")
        val ivParameter = IvParameterSpec(base64DecodeArray(ivStr))
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, ivParameter)
        val encodedEncryptedData = base64DecodeArray(encryptedResultStr)
        val decryptedByteArray = aesCipher.doFinal(encodedEncryptedData)
        val decryptedText = String(decryptedByteArray, Charsets.UTF_8)
        return decryptedText
    }

    private fun modExp(base: BigInteger, exp: BigInteger, mod: BigInteger): BigInteger {
        var result = BigInteger.ONE
        var baseVar = base.mod(mod)
        var expVar = exp

        while (expVar > BigInteger.ZERO) {
            if (expVar.and(BigInteger.ONE) == BigInteger.ONE) {
                result = (result * baseVar).mod(mod)
            }
            baseVar = (baseVar * baseVar).mod(mod)
            expVar = expVar.shiftRight(1)
        }

        return result
    }

    private fun extractSrtSubtitles(subtitle: String): List<Pair<String, String>> {
        val regex = """\[(.*?)](https?://[^\s,"]+\.srt)""".toRegex()
        return regex.findAll(subtitle).map {
            it.groupValues[1] to it.groupValues[2]
        }.toList()
    }

    private fun getNonce(): String {
        val randPart = (Math.random().toString().split(".")[1].toLong()).toString(36)
        val timePart = System.currentTimeMillis().toString(36)
        return randPart + timePart
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

}

class FilemoonV2 : ExtractorApi() {
    override var name = "Filemoon"
    override var mainUrl = "https://filemoon.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val href=app.get(url).document.selectFirst("iframe")?.attr("src") ?:""
        val res= app.get(href, headers = mapOf("Accept-Language" to "en-US,en;q=0.5","sec-fetch-dest" to "iframe")).document.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
        val m3u8= JsUnpacker(res).unpack()?.let { unPacked ->
            Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)
        }
        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                m3u8 ?:"",
                url,
                Qualities.P1080.value,
                type = ExtractorLinkType.M3U8,
            )
        )
    }
}


open class Filesim : ExtractorApi() {
    override val name = "Filesim"
    override val mainUrl = "https://files.im"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var response = app.get(url.replace("/download/", "/e/"), referer = referer)
        val iframe = response.document.selectFirst("iframe")
        if (iframe != null) {
            response = app.get(
                iframe.attr("src"), headers = mapOf(
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Sec-Fetch-Dest" to "iframe"
                ), referer = response.url
            )
        }

        var script = if (!getPacked(response.text).isNullOrEmpty()) {
            getAndUnpack(response.text)
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        }

        if (script == null) {
            val iframeUrl = Regex("""<iframe src="(.*?)"""").find(response.text, 0)?.groupValues?.getOrNull(1)
            if (iframeUrl != null) {
                val iframeResponse = app.get(iframeUrl, referer = null, headers = mapOf("Accept-Language" to "en-US,en;q=0.5"))
                script = if (!getPacked(iframeResponse.text).isNullOrEmpty()) {
                    getAndUnpack(iframeResponse.text)
                } else return
            } else return
        }

        val m3u8 = Regex("file:\\s*\"(.*?m3u8.*?)\"").find(script)?.groupValues?.getOrNull(1)
        generateM3u8(name, m3u8 ?: return, mainUrl).forEach(callback)

        // Extract subtitles
        val tracksJson = Regex("tracks:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(script)?.groupValues?.getOrNull(1) ?: return
        val tracksArray = JSONArray("[$tracksJson]")

        for (i in 0 until tracksArray.length()) {
            val track = tracksArray.getJSONObject(i)
            if (track.optString("kind") == "captions") {
                subtitleCallback(
                    SubtitleFile(
                        track.optString("label"),
                        track.optString("file")
                    )
                )
            }
        }
    }
}