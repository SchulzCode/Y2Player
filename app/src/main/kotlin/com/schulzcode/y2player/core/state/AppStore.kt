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

    fun interface ActionListener {
        fun onAction(action: AppAction, state: AppState)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateListeners = CopyOnWriteArraySet<StateListener>()
    private val effectListeners = CopyOnWriteArraySet<EffectListener>()
    private val actionListeners = CopyOnWriteArraySet<ActionListener>()
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
