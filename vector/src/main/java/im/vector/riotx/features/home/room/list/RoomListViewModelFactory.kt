/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.riotx.features.home.room.list

import im.vector.matrix.android.api.session.Session
import im.vector.riotx.features.home.HomeRoomListObservableStore
import im.vector.riotx.features.share.ShareRoomListObservableStore
import javax.inject.Inject
import javax.inject.Provider

class RoomListViewModelFactory @Inject constructor(private val session: Provider<Session>,
                                                   private val homeRoomListObservableStore: Provider<HomeRoomListObservableStore>,
                                                   private val shareRoomListObservableStore: Provider<ShareRoomListObservableStore>,
                                                   private val alphabeticalRoomComparator: Provider<AlphabeticalRoomComparator>,
                                                   private val chronologicalRoomComparator: Provider<ChronologicalRoomComparator>) : RoomListViewModel.Factory {

    override fun create(initialState: RoomListViewState): RoomListViewModel {
        return RoomListViewModel(
                initialState,
                session.get(),
                if (initialState.displayMode == RoomListFragment.DisplayMode.SHARE) shareRoomListObservableStore.get() else homeRoomListObservableStore.get(),
                alphabeticalRoomComparator.get(),
                chronologicalRoomComparator.get())
    }
}
