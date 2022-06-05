package org.matrix.android.sdk.api.session.room.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.room.powerlevels.Role

/**
 * Class representing the EventType.ROOM_EMOTE state event content.
 */
@JsonClass(generateAdapter = true)
data class RoomEmoteContent(
        @Json(name = "images") val images: Map<String, EmoteImage>? = null,
        // TODO: "pack" support
) {
    companion object {
        const val USAGE_EMOTICON = "emoticon"
        const val USAGE_STICKER = "sticker"
    }
}
