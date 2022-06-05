/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.autocomplete.emoji

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.features.autocomplete.AutocompleteClickListener
import im.vector.app.features.autocomplete.RecyclerViewPresenter
import im.vector.app.features.reactions.data.EmojiDataSource
import im.vector.app.features.reactions.data.EmojiItem
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.getStateEvent
import org.matrix.android.sdk.api.session.room.model.RoomEmoteContent

class AutocompleteEmojiPresenter @AssistedInject constructor(context: Context,
                                                             @Assisted val roomId: String,
                                                             private val session: Session,
                                                             private val vectorPreferences: VectorPreferences,
                                                             private val emojiDataSource: EmojiDataSource,
                                                             private val controller: AutocompleteEmojiController) :
        RecyclerViewPresenter<EmojiItem>(context), AutocompleteClickListener<EmojiItem> {

    private val room by lazy { session.getRoom(roomId)!! }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        controller.listener = this
    }

    fun clear() {
        coroutineScope.coroutineContext.cancelChildren()
        controller.listener = null
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): AutocompleteEmojiPresenter
    }

    override fun instantiateAdapter(): RecyclerView.Adapter<*> {
        return controller.adapter
    }

    override fun onItemClick(t: EmojiItem) {
        dispatchClick(t)
    }

    override fun onQuery(query: CharSequence?) {
        coroutineScope.launch {
            val data = if (query.isNullOrBlank()) {
                // Return common emojis
                emojiDataSource.getQuickReactions()
            } else {
                emojiDataSource.filterWith(query.toString())
            }
            // TODO may want to add headers (compare @room vs @person completion) for
            // - Standard emojis
            // - Room-specific emotes
            // - Global emotes (exported from other rooms)
            val emoteData = if (vectorPreferences.isMarkdownEnabled()) {
                val images = room.getStateEvent(EventType.ROOM_EMOTES)?.content?.toModel<RoomEmoteContent>()?.images.orEmpty()
                images.filter {
                    val usages = it.value.usage
                    usages.isNullOrEmpty() || RoomEmoteContent.USAGE_EMOTICON in usages
                }.filter {
                    query == null || it.key.contains(query, true)
                }.map {
                    EmojiItem(it.key, "", mxcUrl = it.value.url)
                }.sortedBy { it.name }
                .distinct()
            } else {
                emptyList()
            }
            controller.setData(emoteData + data)
        }
    }
}
