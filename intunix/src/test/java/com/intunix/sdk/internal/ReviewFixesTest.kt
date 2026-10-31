package com.intunix.sdk.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regressions from the 1.5.1 review. */
class ReviewFixesTest {
    @Test fun nonFiniteNumbersBecomeNullInsteadOfThrowing() {
        val o = toJson(mapOf("nan" to Double.NaN, "inf" to Float.POSITIVE_INFINITY, "ok" to 1.5, "when" to java.util.Date(0))) as JSONObject
        assertEquals(JSONObject.NULL, o.get("nan"))
        assertEquals(JSONObject.NULL, o.get("inf"))
        assertEquals(1.5, o.getDouble("ok"), 0.0)
        assertEquals(java.util.Date(0).toString(), o.getString("when"))
    }

    @Test fun offlineRemoveDropsOnlyUploadedEvents() {
        val store = MemoryStore()
        val off = OfflineStore(store) { 0 }
        val a = JSONObject().put("name", "a").put("timestamp", 0)
        val b = JSONObject().put("name", "b").put("timestamp", 0)
        off.save(listOf(a, b))
        off.remove(listOf(a))
        assertEquals(listOf("b"), off.readAll().map { it.getString("name") })
        off.remove(listOf(b))
        assertEquals(null, store.get("intunix:offline"))
    }
}
