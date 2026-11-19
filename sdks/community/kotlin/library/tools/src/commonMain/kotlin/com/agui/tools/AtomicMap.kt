package com.agui.tools

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A copy-on-write map that coroutines on any thread can read and update without locking.
 * Updates retry until no other update raced them, so [update] must not have side effects.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class AtomicMap<K, V> {
    private val ref = AtomicReference(emptyMap<K, V>())

    val value: Map<K, V> get() = ref.load()

    operator fun get(key: K): V? = value[key]

    /** Replaces the map with [transform] of the current one and returns the new map. */
    fun update(transform: (Map<K, V>) -> Map<K, V>): Map<K, V> {
        while (true) {
            val current = ref.load()
            val next = transform(current)
            if (ref.compareAndSet(current, next)) return next
        }
    }
}
