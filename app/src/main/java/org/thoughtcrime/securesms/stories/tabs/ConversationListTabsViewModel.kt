package org.thoughtcrime.securesms.stories.tabs

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.subjects.PublishSubject
import io.reactivex.rxjava3.subjects.Subject
import org.thoughtcrime.securesms.util.livedata.Store

class ConversationListTabsViewModel(repository: ConversationListTabRepository) : ViewModel() {
  private val store = Store(ConversationListTabsState())

  val stateSnapshot: ConversationListTabsState = store.state
  val state: LiveData<ConversationListTabsState> = store.stateLiveData
  val disposables = CompositeDisposable()

  private val internalTabClickEvents: Subject<ConversationListTab> = PublishSubject.create()
  val tabClickEvents: Observable<ConversationListTab> = internalTabClickEvents

  init {
    disposables += repository.getNumberOfUnreadConversations().subscribe { unreadChats ->
      store.update { it.copy(unreadChatsCount = unreadChats) }
    }

    disposables += repository.getNumberOfUnseenStories().subscribe { unseenStories ->
      store.update { it.copy(unreadStoriesCount = unseenStories) }
    }
  }

  override fun onCleared() {
    disposables.clear()
  }

  fun onChatsSelected() {
    internalTabClickEvents.onNext(ConversationListTab.CHATS)
    store.update { it.copy(tab = ConversationListTab.CHATS) }
  }

  fun onStoriesSelected() {
    internalTabClickEvents.onNext(ConversationListTab.STORIES)
    store.update { it.copy(tab = ConversationListTab.STORIES) }
  }

  fun onSearchOpened() {
    store.update { it.copy(visibilityState = it.visibilityState.copy(isSearchOpen = true)) }
  }

  fun onSearchClosed() {
    store.update { it.copy(visibilityState = it.visibilityState.copy(isSearchOpen = false)) }
  }

  fun onMultiSelectStarted() {
    store.update { it.copy(visibilityState = it.visibilityState.copy(isMultiSelectOpen = true)) }
  }

  fun onMultiSelectFinished() {
    store.update { it.copy(visibilityState = it.visibilityState.copy(isMultiSelectOpen = false)) }
  }

  fun isShowingArchived(isShowingArchived: Boolean) {
    store.update { it.copy(visibilityState = it.visibilityState.copy(isShowingArchived = isShowingArchived)) }
  }

  class Factory(private val repository: ConversationListTabRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return modelClass.cast(ConversationListTabsViewModel(repository)) as T
    }
  }
}
