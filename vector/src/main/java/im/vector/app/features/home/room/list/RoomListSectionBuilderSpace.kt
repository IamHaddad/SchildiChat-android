/*
 * Copyright (c) 2021 New Vector Ltd
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

package im.vector.app.features.home.room.list

import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asFlow
import androidx.lifecycle.liveData
import androidx.paging.PagedList
import com.airbnb.mvrx.Async
import de.spiritcroc.matrixsdk.util.DbgUtil
import de.spiritcroc.matrixsdk.util.Dimber
import im.vector.app.AppStateHandler
import im.vector.app.R
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.home.RoomListDisplayMode
import im.vector.app.features.invite.AutoAcceptInvites
import im.vector.app.features.invite.showInvites
import im.vector.app.space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.query.RoomCategoryFilter
import org.matrix.android.sdk.api.query.RoomTagQueryFilter
import org.matrix.android.sdk.api.query.SpaceFilter
import org.matrix.android.sdk.api.query.toActiveSpaceOrOrphanRooms
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.room.RoomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.UpdatableLivePageResult
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.summary.RoomAggregateNotificationCount
import timber.log.Timber
import kotlin.math.exp

class RoomListSectionBuilderSpace(
        private val session: Session,
        private val stringProvider: StringProvider,
        private val appStateHandler: AppStateHandler,
        private val viewModelScope: CoroutineScope,
        private val autoAcceptInvites: AutoAcceptInvites,
        private val onUpdatable: (UpdatableLivePageResult) -> Unit,
        private val suggestedRoomJoiningState: LiveData<Map<String, Async<Unit>>>,
        private val onlyOrphansInHome: Boolean = false
) : RoomListSectionBuilder {

    companion object {
        const val SPACE_ID_FOLLOW_APP = "de.spiritcroc.riotx.SPACE_ID_FOLLOW_APP"
    }

    private val pagedListConfig = PagedList.Config.Builder()
            .setPageSize(10)
            .setInitialLoadSizeHint(20)
            .setEnablePlaceholders(true)
            .setPrefetchDistance(10)
            .build()

    private val dimber = Dimber("ViewPager", DbgUtil.DBG_VIEW_PAGER)

    override fun buildSections(mode: RoomListDisplayMode, explicitSpaceId: String?): List<RoomsSection> {
        dimber.i { "Build sections for $mode, $explicitSpaceId" }
        val sections = mutableListOf<RoomsSection>()
        val activeSpaceAwareQueries = mutableListOf<RoomListViewModel.ActiveSpaceQueryUpdater>()
        when (mode) {
            RoomListDisplayMode.PEOPLE        -> {
                // 4 sections Invites / Fav / Dms / Low Priority
                buildDmSections(sections, activeSpaceAwareQueries, explicitSpaceId)
            }
            RoomListDisplayMode.ROOMS         -> {
                // 6 sections invites / Fav / Rooms / Low Priority / Server notice / Suggested rooms
                buildRoomsSections(sections, activeSpaceAwareQueries, explicitSpaceId)
            }
            RoomListDisplayMode.ALL           -> {
                buildUnifiedSections(sections, activeSpaceAwareQueries, explicitSpaceId)
            }
            RoomListDisplayMode.FILTERED      -> {
                // Used when searching for rooms
                buildFilteredSection(sections)
            }
            RoomListDisplayMode.NOTIFICATIONS -> {
                buildNotificationsSection(sections, activeSpaceAwareQueries, explicitSpaceId)
            }
        }

        if (explicitSpaceId == SPACE_ID_FOLLOW_APP) {
            appStateHandler.selectedRoomGroupingFlow
                    .distinctUntilChanged()
                    .onEach { groupingMethod ->
                        val selectedSpace = groupingMethod.orNull()?.space()
                        activeSpaceAwareQueries.onEach { updater ->
                            updater.updateForSpaceId(selectedSpace?.roomId)
                        }
                    }.launchIn(viewModelScope)
        } else {
            activeSpaceAwareQueries.onEach { updater ->
                updater.updateForSpaceId(explicitSpaceId)
            }
        }

        return sections
    }

    private fun buildUnifiedSections(sections: MutableList<RoomsSection>, activeSpaceAwareQueries: MutableList<RoomListViewModel.ActiveSpaceQueryUpdater>, explicitSpaceId: String?) {
        addSection(
                sections = sections,
                activeSpaceUpdaters = activeSpaceAwareQueries,
                nameRes = R.string.invitations_header,
                notifyOfLocalEcho = true,
                explicitSpaceId = explicitSpaceId,
                spaceFilterStrategy = RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL,
                countRoomAsNotif = true
        ) {
            it.memberships = listOf(Membership.INVITE)
        }

        addSection(
                sections,
                activeSpaceAwareQueries,
                R.string.bottom_action_favourites,
                false,
                spaceFilterStrategy = if (onlyOrphansInHome) {
                    RoomListViewModel.SpaceFilterStrategy.ORPHANS_IF_SPACE_NULL
                } else {
                    RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL
                },
                explicitSpaceId = explicitSpaceId,
        ) {
            it.memberships = listOf(Membership.JOIN)
            it.roomTagQueryFilter = RoomTagQueryFilter(true, null, null)
        }

        addSection(
                sections = sections,
                activeSpaceUpdaters = activeSpaceAwareQueries,
                nameRes = R.string.normal_priority_header,
                notifyOfLocalEcho = false,
                explicitSpaceId = explicitSpaceId,
                spaceFilterStrategy = if (onlyOrphansInHome) {
                    RoomListViewModel.SpaceFilterStrategy.ORPHANS_IF_SPACE_NULL
                } else {
                    RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL
                }
        ) {
            it.memberships = listOf(Membership.JOIN)
            it.roomTagQueryFilter = RoomTagQueryFilter(false, false, false)
        }

        addSection(
                sections = sections,
                activeSpaceUpdaters = activeSpaceAwareQueries,
                nameRes = R.string.low_priority_header,
                notifyOfLocalEcho = false,
                explicitSpaceId = explicitSpaceId,
                spaceFilterStrategy = if (onlyOrphansInHome) {
                    RoomListViewModel.SpaceFilterStrategy.ORPHANS_IF_SPACE_NULL
                } else {
                    RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL
                }
        ) {
            it.memberships = listOf(Membership.JOIN)
            it.roomTagQueryFilter = RoomTagQueryFilter(null, true, null)
        }

        addSection(
                sections = sections,
                activeSpaceUpdaters = activeSpaceAwareQueries,
                nameRes = R.string.system_alerts_header,
                notifyOfLocalEcho = false,
                explicitSpaceId = explicitSpaceId,
                spaceFilterStrategy = if (onlyOrphansInHome) {
                    RoomListViewModel.SpaceFilterStrategy.ORPHANS_IF_SPACE_NULL
                } else {
                    RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL
                }
        ) {
            it.memberships = listOf(Membership.JOIN)
            it.roomTagQueryFilter = RoomTagQueryFilter(null, null, true)
        }

        addSuggestedRoomsSection(sections, explicitSpaceId)
    }

    private fun buildRoomsSections(sections: MutableList<RoomsSection>,
                                   activeSpaceAwareQueries: MutableList<RoomListViewModel.ActiveSpaceQueryUpdater>,
                                   explicitSpaceId: String?) {
        if (autoAcceptInvites.showInvites()) {
            addSection(
                    sections = sections,
                    activeSpaceUpdaters = activeSpaceAwareQueries,
                    nameRes = R.string.invitations_header,
                    notifyOfLocalEcho = true,
                    explicitSpaceId = explicitSpaceId,
                    spaceFilterStrategy = RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL,
                    countRoomAsNotif = true
            ) {
                it.memberships = listOf(Membership.INVITE)
                it.roomCategoryFilter = RoomCategoryFilter.ONLY_ROOMS
            }
        }

        addSection(
                sections,
                activeSpaceAwareQueries,
                R.string.bottom_action_favourites,
                false,
                spaceFilterStrategy = if (onlyOrphansInHome) {
                    RoomListViewModel.SpaceFilterStrategy.ORPHANS_IF_SPACE_NULL
                } else {
                    RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL
                },
                explicitSpaceId = explicitSpaceId,
        ) {
            it.memberships = listOf(Membership.JOIN)
            it.roomCategoryFilter = RoomCategoryFilter.ONLY_ROOMS
            it.roomTagQueryFilter = RoomTagQueryFilter(true, null, null)
        }

        addSection(
                sections = sections,
                activeSpaceUpdaters = activeSpaceAwareQueries,
                nameRes = R.string.bottom_action_rooms,
                notifyOfLocalEcho = false,
                explicitSpaceId = explicitSpaceId,
                spaceFilterStrategy = if (onlyOrphansInHome) {
                    RoomListViewModel.SpaceFilterStrategy.ORPHANS_IF_SPACE_NULL
                } else {
                    RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL
                }
        ) {
            it.memberships = listOf(Membership.JOIN)
            it.roomCategoryFilter = RoomCategoryFilter.ONLY_ROOMS
            it.roomTagQueryFilter = RoomTagQueryFilter(false, false, false)
        }

        addSection(
                sections = sections,
                activeSpaceUpdaters = activeSpaceAwareQueries,
                nameRes = R.string.low_priority_header,
                notifyOfLocalEcho = false,
                explicitSpaceId = explicitSpaceId,
                spaceFilterStrategy = if (onlyOrphansInHome) {
                    RoomListViewModel.SpaceFilterStrategy.ORPHANS_IF_SPACE_NULL
                } else {
                    RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL
                }
        ) {
            it.memberships = listOf(Membership.JOIN)
            it.roomCategoryFilter = RoomCategoryFilter.ONLY_ROOMS
            it.roomTagQueryFilter = RoomTagQueryFilter(null, true, null)
        }

        addSection(
                sections = sections,
                activeSpaceUpdaters = activeSpaceAwareQueries,
                nameRes = R.string.system_alerts_header,
                notifyOfLocalEcho = false,
                explicitSpaceId = explicitSpaceId,
                spaceFilterStrategy = if (onlyOrphansInHome) {
                    RoomListViewModel.SpaceFilterStrategy.ORPHANS_IF_SPACE_NULL
                } else {
                    RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL
                }
        ) {
            it.memberships = listOf(Membership.JOIN)
            it.roomCategoryFilter = RoomCategoryFilter.ONLY_ROOMS
            it.roomTagQueryFilter = RoomTagQueryFilter(null, null, true)
        }

        addSuggestedRoomsSection(sections, explicitSpaceId)
    }

    private fun addSuggestedRoomsSection(sections: MutableList<RoomsSection>,
                                         explicitSpaceId: String?) {
        // add suggested rooms
        val suggestedRoomsFlow = if (explicitSpaceId == SPACE_ID_FOLLOW_APP) { // MutableLiveData<List<SpaceChildInfo>>()
                appStateHandler.selectedRoomGroupingFlow
                        .distinctUntilChanged()
                        .flatMapLatest { groupingMethod ->
                            val selectedSpace = groupingMethod.orNull()?.space()
                            if (selectedSpace == null) {
                                flowOf(emptyList())
                            } else {
                                liveData(context = viewModelScope.coroutineContext + Dispatchers.IO) {
                                    val spaceSum = tryOrNull {
                                        session.spaceService()
                                                .querySpaceChildren(selectedSpace.roomId, suggestedOnly = true, null, null)
                                    }
                                    val value = spaceSum?.children.orEmpty().distinctBy { it.childRoomId }
                                    // i need to check if it's already joined.
                                    val filtered = value.filter {
                                        session.getRoomSummary(it.childRoomId)?.membership?.isActive() != true
                                    }
                                    emit(filtered)
                                }.asFlow()
                            }
                        }
        } else {
            if (explicitSpaceId == null) {
                flowOf(emptyList())
            } else {
                liveData(context = viewModelScope.coroutineContext + Dispatchers.IO) {
                    val spaceSum = tryOrNull {
                        session.spaceService()
                                .querySpaceChildren(explicitSpaceId, suggestedOnly = true, null, null)
                    }
                    val value = spaceSum?.children.orEmpty().distinctBy { it.childRoomId }
                    // i need to check if it's already joined.
                    val filtered = value.filter {
                        session.getRoomSummary(it.childRoomId)?.membership?.isActive() != true
                    }
                    emit(filtered)
                }.asFlow()
            }
        }

        val liveSuggestedRooms = MutableLiveData<SuggestedRoomInfo>()
        combine(
                suggestedRoomsFlow,
                suggestedRoomJoiningState.asFlow()
        ) { rooms, joinStates ->
            SuggestedRoomInfo(
                    rooms,
                    joinStates
            )
        }.onEach {
            liveSuggestedRooms.postValue(it)
        }.launchIn(viewModelScope)

        sections.add(
                RoomsSection(
                        sectionName = stringProvider.getString(R.string.suggested_header),
                        liveSuggested = liveSuggestedRooms,
                        notifyOfLocalEcho = false,
                        itemCount = suggestedRoomsFlow.map { suggestions -> suggestions.size }
                )
        )
    }

    private fun buildDmSections(sections: MutableList<RoomsSection>,
                                activeSpaceAwareQueries: MutableList<RoomListViewModel.ActiveSpaceQueryUpdater>,
                                explicitSpaceId: String?) {
        if (autoAcceptInvites.showInvites()) {
            addSection(
                    sections = sections,
                    activeSpaceUpdaters = activeSpaceAwareQueries,
                    nameRes = R.string.invitations_header,
                    notifyOfLocalEcho = true,
                    explicitSpaceId = explicitSpaceId,
                    spaceFilterStrategy = RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL,
                    countRoomAsNotif = true
            ) {
                it.memberships = listOf(Membership.INVITE)
                it.roomCategoryFilter = RoomCategoryFilter.ONLY_DM
            }
        }

        addSection(
                sections,
                activeSpaceAwareQueries,
                R.string.bottom_action_favourites,
                false,
                spaceFilterStrategy = if (onlyOrphansInHome) {
                    RoomListViewModel.SpaceFilterStrategy.ORPHANS_IF_SPACE_NULL
                } else {
                    RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL
                },
                explicitSpaceId = explicitSpaceId
        ) {
            it.memberships = listOf(Membership.JOIN)
            it.roomCategoryFilter = RoomCategoryFilter.ONLY_DM
            it.roomTagQueryFilter = RoomTagQueryFilter(true, null, null)
        }

        addSection(
                sections,
                activeSpaceAwareQueries,
                R.string.bottom_action_people_x,
                false,
                RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL,
                explicitSpaceId = explicitSpaceId
        ) {
            it.memberships = listOf(Membership.JOIN)
            it.roomCategoryFilter = RoomCategoryFilter.ONLY_DM
            it.roomTagQueryFilter = RoomTagQueryFilter(false, false, null)
        }

        addSection(
                sections,
                activeSpaceAwareQueries,
                R.string.low_priority_header,
                false,
                RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL,
                explicitSpaceId = explicitSpaceId
        ) {
            it.memberships = listOf(Membership.JOIN)
            it.roomCategoryFilter = RoomCategoryFilter.ONLY_DM
            it.roomTagQueryFilter = RoomTagQueryFilter(false, true, null)
        }

    }

    private fun AppStateHandler.explicitOrSafeActiveSpaceId(explicitSpaceId: String?): String? {
        return if (explicitSpaceId == SPACE_ID_FOLLOW_APP) {
            safeActiveSpaceId()
        } else {
            explicitSpaceId
        }
    }

    private fun buildNotificationsSection(sections: MutableList<RoomsSection>,
                                          activeSpaceAwareQueries: MutableList<RoomListViewModel.ActiveSpaceQueryUpdater>,
                                          explicitSpaceId: String?) {
        if (autoAcceptInvites.showInvites()) {
            addSection(
                    sections = sections,
                    activeSpaceUpdaters = activeSpaceAwareQueries,
                    nameRes = R.string.invitations_header,
                    notifyOfLocalEcho = true,
                    explicitSpaceId = explicitSpaceId,
                    spaceFilterStrategy = if (onlyOrphansInHome) {
                        RoomListViewModel.SpaceFilterStrategy.ORPHANS_IF_SPACE_NULL
                    } else {
                        RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL
                    },
                    countRoomAsNotif = true
            ) {
                it.memberships = listOf(Membership.INVITE)
            }
        }

        addSection(
                sections = sections,
                activeSpaceUpdaters = activeSpaceAwareQueries,
                nameRes = R.string.bottom_action_rooms,
                notifyOfLocalEcho = false,
                explicitSpaceId = explicitSpaceId,
                spaceFilterStrategy = if (onlyOrphansInHome) {
                    RoomListViewModel.SpaceFilterStrategy.ORPHANS_IF_SPACE_NULL
                } else {
                    RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL
                }
        ) {
            it.memberships = listOf(Membership.JOIN)
            it.roomCategoryFilter = RoomCategoryFilter.ONLY_WITH_NOTIFICATIONS
        }
    }

    private fun buildFilteredSection(sections: MutableList<RoomsSection>) {
        // Used when searching for rooms
        withQueryParams(
                {
                    it.memberships = Membership.activeMemberships()
                },
                { queryParams ->
                    val name = stringProvider.getString(R.string.bottom_action_rooms)
                    val updatableFilterLivePageResult = session.roomService().getFilteredPagedRoomSummariesLive(queryParams, getFlattenParents = true)
                    onUpdatable(updatableFilterLivePageResult)

                    val itemCountFlow = updatableFilterLivePageResult.livePagedList.asFlow()
                            .flatMapLatest { session.roomService().getRoomCountLive(updatableFilterLivePageResult.queryParams).asFlow() }
                            .distinctUntilChanged()

                    sections.add(
                            RoomsSection(
                                    sectionName = name,
                                    livePages = updatableFilterLivePageResult.livePagedList,
                                    itemCount = itemCountFlow
                            )
                    )
                }
        )
    }

    private fun addSection(sections: MutableList<RoomsSection>,
                           activeSpaceUpdaters: MutableList<RoomListViewModel.ActiveSpaceQueryUpdater>,
                           @StringRes nameRes: Int,
                           notifyOfLocalEcho: Boolean = false,
                           spaceFilterStrategy: RoomListViewModel.SpaceFilterStrategy = RoomListViewModel.SpaceFilterStrategy.NONE,
                           countRoomAsNotif: Boolean = false,
                           explicitSpaceId: String?,
                           query: (RoomSummaryQueryParams.Builder) -> Unit) {
        withQueryParams(query) { roomQueryParams ->
            val updatedQueryParams = roomQueryParams.process(spaceFilterStrategy, appStateHandler.explicitOrSafeActiveSpaceId(explicitSpaceId))
            val liveQueryParams = MutableStateFlow(updatedQueryParams)
            val itemCountFlow = liveQueryParams
                    .flatMapLatest {
                        session.roomService().getRoomCountLive(it).asFlow()
                    }
                    .flowOn(Dispatchers.Main)
                    .distinctUntilChanged()

            val name = stringProvider.getString(nameRes)
            val filteredPagedRoomSummariesLive = session.roomService().getFilteredPagedRoomSummariesLive(
                    roomQueryParams.process(spaceFilterStrategy, appStateHandler.explicitOrSafeActiveSpaceId(explicitSpaceId)),
                    pagedListConfig
            )
            when (spaceFilterStrategy) {
                RoomListViewModel.SpaceFilterStrategy.ORPHANS_IF_SPACE_NULL -> {
                    activeSpaceUpdaters.add(object : RoomListViewModel.ActiveSpaceQueryUpdater {
                        override fun updateForSpaceId(roomId: String?) {
                            filteredPagedRoomSummariesLive.queryParams = roomQueryParams.copy(
                                    spaceFilter = roomId?.toActiveSpaceOrOrphanRooms()
                            )
                            liveQueryParams.update { filteredPagedRoomSummariesLive.queryParams }
                        }
                    })
                }
                RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL     -> {
                    activeSpaceUpdaters.add(object : RoomListViewModel.ActiveSpaceQueryUpdater {
                        override fun updateForSpaceId(roomId: String?) {
                            if (roomId != null) {
                                filteredPagedRoomSummariesLive.queryParams = roomQueryParams.copy(
                                        spaceFilter = SpaceFilter.ActiveSpace(roomId)
                                )
                            } else {
                                filteredPagedRoomSummariesLive.queryParams = roomQueryParams.copy(
                                        spaceFilter = null
                                )
                            }
                            liveQueryParams.update { filteredPagedRoomSummariesLive.queryParams }
                        }
                    })
                }
                RoomListViewModel.SpaceFilterStrategy.NONE                  -> {
                    // we ignore current space for this one
                }
            }

            val livePagedList = filteredPagedRoomSummariesLive.livePagedList
            // use it also as a source to update count
            livePagedList.asFlow()
                    .onEach {
                        Timber.v("Thread space list: ${Thread.currentThread()}")
                        sections.find { it.sectionName == name }
                                ?.notificationCount
                                ?.postValue(
                                        if (countRoomAsNotif) {
                                            RoomAggregateNotificationCount(it.size, it.size, 0)
                                        } else {
                                            session.roomService().getNotificationCountForRooms(
                                                    roomQueryParams.process(spaceFilterStrategy, appStateHandler.explicitOrSafeActiveSpaceId(explicitSpaceId))
                                            )
                                        }
                                )
                    }
                    .flowOn(Dispatchers.Default)
                    .launchIn(viewModelScope)

            sections.add(
                    RoomsSection(
                            sectionName = name,
                            livePages = livePagedList,
                            notifyOfLocalEcho = notifyOfLocalEcho,
                            itemCount = itemCountFlow
                    )
            )
        }
    }

    private fun withQueryParams(builder: (RoomSummaryQueryParams.Builder) -> Unit, block: (RoomSummaryQueryParams) -> Unit) {
        block(roomSummaryQueryParams { builder.invoke(this) })
    }

    internal fun RoomSummaryQueryParams.process(spaceFilter: RoomListViewModel.SpaceFilterStrategy, currentSpace: String?): RoomSummaryQueryParams {
        return when (spaceFilter) {
            RoomListViewModel.SpaceFilterStrategy.ORPHANS_IF_SPACE_NULL -> {
                copy(
                        spaceFilter = currentSpace?.toActiveSpaceOrOrphanRooms()
                )
            }
            RoomListViewModel.SpaceFilterStrategy.ALL_IF_SPACE_NULL     -> {
                copy(
                        spaceFilter = currentSpace?.let { SpaceFilter.ActiveSpace(it) }
                )
            }
            RoomListViewModel.SpaceFilterStrategy.NONE                  -> this
        }
    }
}
