package link.wyrd.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class EnrollRequest(
    val deviceId: String,
    val model: String,
    val manufacturer: String,
    val androidSdk: Int,
    val attestationChain: List<String>,
)

@Serializable
data class EnrollResponse(
    val ok: Boolean,
    val message: String? = null,
    val rotatedToken: String? = null,
    val serverPublicKey: String? = null,
    val profileExpiresAt: Long? = null,
)

@Serializable
data class Command(
    val id: String,
    val type: String,
    val payload: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class CommandResult(
    val id: String,
    val ok: Boolean,
    val message: String? = null
)
