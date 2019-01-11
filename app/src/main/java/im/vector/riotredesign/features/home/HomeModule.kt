package im.vector.riotredesign.features.home

import im.vector.riotredesign.features.home.group.SelectedGroupHolder
import im.vector.riotredesign.features.home.room.VisibleRoomHolder
import im.vector.riotredesign.features.home.room.detail.timeline.MessageItemFactory
import im.vector.riotredesign.features.home.room.detail.timeline.TextItemFactory
import im.vector.riotredesign.features.home.room.detail.timeline.TimelineDateFormatter
import im.vector.riotredesign.features.home.room.detail.timeline.TimelineEventController
import org.koin.dsl.module.module

class HomeModule(private val homeActivity: HomeActivity) {

    val definition = module(override = true) {

        single {
            TimelineDateFormatter(get())
        }

        single {
            MessageItemFactory(get())
        }

        single {
            TextItemFactory()
        }

        single {
            HomeNavigator()
        }

        factory { (roomId: String) ->
            TimelineEventController(roomId, get(), get(), get())
        }

        single {
            SelectedGroupHolder()
        }

        single {
            VisibleRoomHolder()
        }

        single {
            HomePermalinkHandler(get())
        }


    }
}