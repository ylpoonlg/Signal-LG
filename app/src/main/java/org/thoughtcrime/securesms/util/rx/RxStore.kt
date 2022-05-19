package org.thoughtcrime.securesms.util.rx

import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.processors.BehaviorProcessor
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject

/**
 * Rx replacement for Store.
 * Actions are run on the computation thread by default.
 */
class RxStore<T : Any>(
  defaultValue: T,
  private val scheduler: Scheduler = Schedulers.computation()
) {

  private val behaviorProcessor = BehaviorProcessor.createDefault(defaultValue)
  private val actionSubject = PublishSubject.create<(T) -> T>().toSerialized()

  val state: T get() = behaviorProcessor.value!!
  val stateFlowable: Flowable<T> = behaviorProcessor

  init {
    actionSubject
      .observeOn(scheduler)
      .scan(defaultValue) { v, f -> f(v) }
      .subscribe { behaviorProcessor.onNext(it) }
  }

  fun update(transformer: (T) -> T) {
    actionSubject.onNext(transformer)
  }
}
