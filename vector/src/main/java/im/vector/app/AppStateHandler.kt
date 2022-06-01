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

package im.vector.app

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.asFlow
import arrow.core.Option
import arrow.core.getOrElse
import de.spiritcroc.matrixsdk.util.DbgUtil
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.utils.BehaviorDataSource
import im.vector.app.features.analytics.AnalyticsTracker
import im.vector.app.features.analytics.plan.UserProperties
import im.vector.app.features.session.coroutineScope
import im.vector.app.features.ui.UiStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.group.model.GroupSummary
import org.matrix.android.sdk.api.session.initsync.SyncStatusService
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed class RoomGroupingMethod {
    data class ByLegacyGroup(val groupSummary: GroupSummary?) : RoomGroupingMethod()
    data class BySpace(val spaceSummary: RoomSummary?) : RoomGroupingMethod()
}

enum class SelectSpaceFrom {
    // Initialized / uiStateRepository
    INIT,
    // Swiped in home pager
    SWIPE,
    // Persisted after swipe in home pager
    PERSIST_SWIPE,
    // Selected from non-pager UI
    SELECT,
}

fun RoomGroupingMethod.space() = (this as? RoomGroupingMethod.BySpace)?.spaceSummary
fun RoomGroupingMethod.group() = (this as? RoomGroupingMethod.ByLegacyGroup)?.groupSummary

/**
 * This class handles the global app state.
 * It requires to be added to ProcessLifecycleOwner.get().lifecycle
 */
// TODO Keep this class for now, will maybe be used fro Space
@Singleton
class AppStateHandler @Inject constructor(
        private val sessionDataSource: ActiveSessionDataSource,
        private val uiStateRepository: UiStateRepository,
        private val activeSessionHolder: ActiveSessionHolder,
        private val analyticsTracker: AnalyticsTracker
) : DefaultLifecycleObserver {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    ///private val selectedSpaceDataSource = BehaviorDataSource<Option<RoomGroupingMethod>>(Option.empty())

    // SchildiChat: the boolean means pendingSwipe and defaults to false. Set to true for swiping spaces, so you want to ignore updates which have pendingSwipe = true.
    // Call it different then the upstream one so we don't forget adding `first` to upstream's logic.
    private val selectedSpaceDataSourceSc = BehaviorDataSource<Option<Pair<RoomGroupingMethod, SelectSpaceFrom>>>(Option.empty())

    val selectedRoomGroupingFlow = selectedSpaceDataSourceSc.stream().map { it.map { it.first } }
    val selectedRoomGroupingFlowIgnoreSwipe = selectedSpaceDataSourceSc.stream()
            .filter { it.getOrElse{ null }?.second != SelectSpaceFrom.SWIPE }

    private val spaceBackstack = ArrayDeque<String?>()

    fun getCurrentRoomGroupingMethod(): RoomGroupingMethod? {
        // XXX we should somehow make it live :/ just a work around
        // For example just after creating a space and switching to it the
        // name in the app Bar could show Empty Room, and it will not update unless you
        // switch space
        return selectedSpaceDataSourceSc.currentValue?.orNull()?.first?.let {
            if (it is RoomGroupingMethod.BySpace) {
                // try to refresh sum?
                it.spaceSummary?.roomId?.let { activeSessionHolder.getSafeActiveSession()?.roomService()?.getRoomSummary(it) }?.let {
                    RoomGroupingMethod.BySpace(it)
                } ?: it
            } else it
        }
    }

    fun setCurrentSpace(spaceId: String?, session: Session? = null, persistNow: Boolean = false, isForwardNavigation: Boolean = true, from: SelectSpaceFrom = SelectSpaceFrom.SELECT) {
        val currentSpace = (selectedSpaceDataSourceSc.currentValue?.orNull()?.first as? RoomGroupingMethod.BySpace)?.space()
        val uSession = session ?: activeSessionHolder.getSafeActiveSession() ?: return
        if (currentSpace != null && spaceId == currentSpace.roomId) return
        val spaceSum = spaceId?.let { uSession.getRoomSummary(spaceId) }

        if (DbgUtil.isDbgEnabled(DbgUtil.DBG_VIEW_PAGER) && from == SelectSpaceFrom.SELECT) {
            // We want a stack trace
            Timber.w(Exception("Home pager: setCurrentSpace/SELECT"))
        }

        if (isForwardNavigation && from in listOf(SelectSpaceFrom.SELECT, SelectSpaceFrom.INIT)) {
            spaceBackstack.addLast(currentSpace?.roomId)
        }

        if (persistNow) {
            uiStateRepository.storeGroupingMethod(true, uSession.sessionId)
            uiStateRepository.storeSelectedSpace(spaceSum?.roomId, uSession.sessionId)
        }

        selectedSpaceDataSourceSc.post(Option.just(Pair(RoomGroupingMethod.BySpace(spaceSum), from)))
        if (spaceId != null) {
            uSession.coroutineScope.launch(Dispatchers.IO) {
                tryOrNull {
                    uSession.getRoom(spaceId)?.membershipService()?.loadRoomMembersIfNeeded()
                }
            }
        }
    }

    fun setCurrentGroup(groupId: String?, session: Session? = null) {
        val uSession = session ?: activeSessionHolder.getSafeActiveSession() ?: return
        if (selectedSpaceDataSourceSc.currentValue?.orNull()?.first is RoomGroupingMethod.ByLegacyGroup &&
                groupId == selectedSpaceDataSourceSc.currentValue?.orNull()?.first?.group()?.groupId) return
        val activeGroup = groupId?.let { uSession.groupService().getGroupSummary(groupId) }
        selectedSpaceDataSourceSc.post(Option.just(Pair(RoomGroupingMethod.ByLegacyGroup(activeGroup), SelectSpaceFrom.SELECT)))
        if (groupId != null) {
            uSession.coroutineScope.launch {
                tryOrNull {
                    uSession.groupService().getGroup(groupId)?.fetchGroupData()
                }
            }
        }
    }

    private fun observeActiveSession() {
        sessionDataSource.stream()
                .distinctUntilChanged()
                .onEach {
                    // sessionDataSource could already return a session while activeSession holder still returns null
                    it.orNull()?.let { session ->
                        if (uiStateRepository.isGroupingMethodSpace(session.sessionId)) {
                            setCurrentSpace(uiStateRepository.getSelectedSpace(session.sessionId), session, from = SelectSpaceFrom.INIT)
                        } else {
                            setCurrentGroup(uiStateRepository.getSelectedGroup(session.sessionId), session)
                        }
                        //observeSyncStatus(session)
                    }
                }
                .launchIn(coroutineScope)
    }

    /*
    private fun observeSyncStatus(session: Session) {
        session.syncStatusService().getSyncStatusLive()
                .asFlow()
                .filterIsInstance<SyncStatusService.Status.IncrementalSyncDone>()
                .map { session.spaceService().getRootSpaceSummaries().size }
                .distinctUntilChanged()
                .onEach { spacesNumber ->
                    analyticsTracker.updateUserProperties(UserProperties(numSpaces = spacesNumber))
                }.launchIn(session.coroutineScope)
    }
    */

    fun getSpaceBackstack() = spaceBackstack

    fun safeActiveSpaceId(): String? {
        return (selectedSpaceDataSourceSc.currentValue?.orNull()?.first as? RoomGroupingMethod.BySpace)?.spaceSummary?.roomId
    }

    fun safeActiveGroupId(): String? {
        return (selectedSpaceDataSourceSc.currentValue?.orNull()?.first as? RoomGroupingMethod.ByLegacyGroup)?.groupSummary?.groupId
    }

    override fun onResume(owner: LifecycleOwner) {
        observeActiveSession()
    }

    override fun onPause(owner: LifecycleOwner) {
        coroutineScope.coroutineContext.cancelChildren()
        val session = activeSessionHolder.getSafeActiveSession() ?: return
        when (val currentMethod = selectedSpaceDataSourceSc.currentValue?.orNull()?.first ?: RoomGroupingMethod.BySpace(null)) {
            is RoomGroupingMethod.BySpace       -> {
                uiStateRepository.storeGroupingMethod(true, session.sessionId)
                uiStateRepository.storeSelectedSpace(currentMethod.spaceSummary?.roomId, session.sessionId)
            }
            is RoomGroupingMethod.ByLegacyGroup -> {
                uiStateRepository.storeGroupingMethod(false, session.sessionId)
                uiStateRepository.storeSelectedGroup(currentMethod.groupSummary?.groupId, session.sessionId)
            }
        }
    }

    fun persistSelectedSpace() {
        val currentValue = selectedSpaceDataSourceSc.currentValue?.orNull() ?: return
        val currentMethod = currentValue.first as? RoomGroupingMethod.BySpace ?: return
        val uSession = activeSessionHolder.getSafeActiveSession() ?: return

        // We want to persist it, so we also want to remove the pendingSwipe status
        if (currentValue.second == SelectSpaceFrom.SWIPE) {
            selectedSpaceDataSourceSc.post(Option.just(Pair(currentMethod, SelectSpaceFrom.PERSIST_SWIPE)))
        }

        // Persist it across app restarts
        uiStateRepository.storeGroupingMethod(true, uSession.sessionId)
        uiStateRepository.storeSelectedSpace(currentMethod.spaceSummary?.roomId, uSession.sessionId)
    }
}
