package com.schulzcode.y2player.core.state

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

class AppStore(
    initialState: AppState = AppState()
) {
    fun interface StateListener {
        fun onStateChanged(state: AppState)
    }

    fun interface EffectListener {
        fun onEffect(effect: AppEffect)
    }

    /**
     * Observes every reduced action and the state it produced.
     *
     * Exists for diagnostics only: it deliberately hands over the action and the
     * *resulting* state rather than a diff, so a listener can record a name and a
     * screen without the store knowing anything about logging. Keeping this
     * separate from [StateListener] means the renderer is never woken by an
     * action that produced no visible change.
     */
    fun interface ActionListener {
        fun onAction(action: AppAction, state: AppState)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateListeners = CopyOnWriteArraySet<StateListener>()
    private val effectListeners = CopyOnWriteArraySet<EffectListener>()
    private val actionListeners = CopyOnWriteArraySet<ActionListener>()
    // Main-thread-confined re-entrancy queue.
    private val pendingActions = java.util.ArrayDeque<AppAction>()
    private var dispatching = false

    @Volatile
    var state: AppState = initialState
        private set

    fun dispatch(action: AppAction) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dispatch(action) }
            return
        }
        // Actions dispatched re-entrantly (a state or effect listener dispatching from
        // inside its callback) are queued and applied after the current reduction
        // completes, so every listener observes states and effects in dispatch order.
        pendingActions.addLast(action)
        if (dispatching) return
        dispatching = true
        try {
            while (pendingActions.isNotEmpty()) {
                val next = pendingActions.removeFirst()
                val reduction = AppReducer.reduce(state, next)
                state = reduction.state
                actionListeners.forEach { it.onAction(next, state) }
                stateListeners.forEach { it.onStateChanged(state) }
                reduction.effects.forEach { effect ->
                    effectListeners.forEach { it.onEffect(effect) }
                }
            }
        } finally {
            dispatching = false
        }
    }

    fun addStateListener(listener: StateListener, emitImmediately: Boolean = true) {
        stateListeners += listener
        if (emitImmediately) listener.onStateChanged(state)
    }

    fun removeStateListener(listener: StateListener) {
        stateListeners -= listener
    }

    fun addEffectListener(listener: EffectListener) {
        effectListeners += listener
    }

    fun removeEffectListener(listener: EffectListener) {
        effectListeners -= listener
    }

    fun addActionListener(listener: ActionListener) {
        actionListeners += listener
    }

    fun removeActionListener(listener: ActionListener) {
        actionListeners -= listener
    }
}
