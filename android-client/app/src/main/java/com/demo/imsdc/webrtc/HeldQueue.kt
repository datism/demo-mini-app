package com.demo.imsdc.webrtc

/**
 * Items held back while the call is on hold, handed back in arrival order on resume. Bounded: past [cap] the
 * oldest is dropped (a very long hold with a chatty page), so memory cannot grow without limit.
 */
class HeldQueue<T>(private val cap: Int = 2000) {
    private val items = ArrayDeque<T>()
    var dropped = 0
        private set

    @Synchronized fun add(item: T) {
        if (items.size >= cap) { items.removeFirst(); dropped++ }
        items.addLast(item)
    }

    /** Everything held, oldest first; the queue is empty afterwards. */
    @Synchronized fun drain(): List<T> {
        val out = items.toList()
        items.clear()
        dropped = 0
        return out
    }

    @Synchronized fun clear() { items.clear(); dropped = 0 }

    @get:Synchronized val size: Int get() = items.size
}
