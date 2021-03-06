package io.kweb.shoebox

import io.kweb.shoebox.Source.LOCAL
import io.kweb.shoebox.View.VerifyBehavior.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Created by ian on 3/11/17.
 */

class View<T : Any>(val references: Shoebox<Reference>,
                    val viewOf: Shoebox<T>,
                    val verifyBehavior: VerifyBehavior = BLOCKING_VERIFY,
                    val viewBy: (T) -> String) {

    private val newListenerHandler: Long
    private val changeListenerHandler: Long
    private var removeListenerHandler: Long

    init {
        newListenerHandler = viewOf.onNew { keyValue, source ->
            val viewKey = viewBy(keyValue.value)
            if (source == LOCAL) {
                addValue(viewKey, keyValue.key)
            }
            addListeners[viewKey]?.values?.forEach { it(keyValue) }

        }
        changeListenerHandler = viewOf.onChange { previousValue, nextKeyValue, source ->
            if (source == LOCAL) {
                if (previousValue != nextKeyValue.value) {
                    val previousViewKey = viewBy(previousValue)
                    val nextViewKey = viewBy(nextKeyValue.value)
                    if (previousViewKey != nextViewKey) {

                        removeListeners[previousViewKey]?.values?.forEach {
                            try {
                                it(KeyValue(nextKeyValue.key, previousValue))
                            } catch (e: Exception) {
                                e.printStackTrace(System.err)
                            }
                        }
                        removeValue(previousViewKey, nextKeyValue.key)

                        addListeners[nextViewKey]?.values?.forEach {
                            try {
                                it(nextKeyValue)
                            } catch (e: Exception) {
                                e.printStackTrace(System.err)
                            }
                        }
                        addValue(nextViewKey, nextKeyValue.key)
                    }
                }
            }
        }
        removeListenerHandler = viewOf.onRemove { keyValue, source ->
            if (source == LOCAL) {
                val viewKey = viewBy(keyValue.value)
                removeListeners[viewKey]?.values?.forEach { it(keyValue as KeyValue<T?>) }
                removeValue(viewKey, keyValue.key)
            }
        }

        when (verifyBehavior) {
            BLOCKING_VERIFY -> verify()
            ASYNC_VERIFY -> thread { verify() }
        }
    }

    private fun verify() {
        for ((key, value) in viewOf.entries) {
            val refKey = viewBy(value)
            addValue(refKey, key)
        }

        // NOTE: We don't check for superfluous references because these are found and corrected in get()
    }

    operator fun get(viewKey: String): Set<T> = getKeyValues(viewKey).map(KeyValue<T>::value).toSet()

    fun getKeyValues(viewKey: String): Set<KeyValue<T>> {
        val reference = references[viewKey]
        return reference?.keys?.mapNotNull { key ->
            val v = viewOf[key]
            if (v == null) {
                removeListeners[viewKey]?.values?.forEach { it(KeyValue(key, null)) }
                removeValue(viewKey, key)
                null
            } else if (viewBy(v) != viewKey) {
                removeListeners[viewKey]?.values?.forEach { it(KeyValue(key, null)) }
                removeValue(viewKey, key)
                null
            } else {
                KeyValue(key, v)
            }
        }?.toSet() ?: Collections.emptySet()
    }

    private val addListeners = ConcurrentHashMap<String, MutableMap<Long, (KeyValue<T>) -> Unit>>()

    fun onAdd(viewKey : String, listener : (KeyValue<T>) -> Unit) : Long {
        val handle = listenerHandleSource.incrementAndGet()
        addListeners.computeIfAbsent(viewKey) { ConcurrentHashMap() }.put(handle, listener)
        return handle
    }

    fun deleteAddListener(viewKey : String, handle : Long) {
        addListeners.get(viewKey)?.remove(handle)
    }

    private val removeListeners = ConcurrentHashMap<String, MutableMap<Long, (KeyValue<T?>) -> Unit>>()

    fun onRemove(viewKey : String, listener : (KeyValue<T?>) -> Unit) : Long {
        val handle = listenerHandleSource.incrementAndGet()
        removeListeners.computeIfAbsent(viewKey, {ConcurrentHashMap()}).put(handle, listener)
        return handle
    }

    fun deleteRemoveListener(viewKey : String, handle : Long) {
        removeListeners.get(viewKey)?.remove(handle)
    }

    protected fun finalize() {
        viewOf.deleteNewListener(newListenerHandler)
        viewOf.deleteChangeListener(changeListenerHandler)
        viewOf.deleteRemoveListener(removeListenerHandler)
    }

    sealed class EventType<T> {
        data class Add<T>(val key : String, val obj : T) : EventType<T>()
        data class Remove<T>(val key : String, val obj : T?) : EventType<T>()
    }

    internal fun addValue(key: String, value: String) {
        val oldRef = references[key] ?: Reference()
        references[key] = oldRef.addKey(value)
    }

    internal fun removeValue(key: String, value: String) {
        val oldRef = references[key]
        if (oldRef != null) {
            references[key] = oldRef.removeKey(value)
        }
    }

    enum class VerifyBehavior {
        BLOCKING_VERIFY, ASYNC_VERIFY
    }

    data class Reference(val keys: Set<String>) {
        constructor() : this(Collections.emptySet())

        fun removeKey(key: String) = Reference(keys.minus(key))

        fun addKey(key: String) = Reference(keys.plus(key))
    }

    fun orderedSet(key : String, comparator : Comparator<T> = compareBy {it.hashCode()}) = OrderedViewSet(this, key, comparator)
}




