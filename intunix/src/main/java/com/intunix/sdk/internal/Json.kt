package com.intunix.sdk.internal

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) opt(key)?.let { if (it is String) it else null } else null

internal fun JSONObject.int(key: String): Int? = (opt(key) as? Number)?.toInt()
internal fun JSONObject.long(key: String): Long? = (opt(key) as? Number)?.toLong()
internal fun JSONObject.double(key: String): Double? = (opt(key) as? Number)?.toDouble()
internal fun JSONObject.bool(key: String): Boolean? = opt(key) as? Boolean
internal fun JSONObject.obj(key: String): JSONObject? = opt(key) as? JSONObject
internal fun JSONObject.arr(key: String): JSONArray? = opt(key) as? JSONArray

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { opt(it) as? JSONObject }
internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { opt(it) as? String }

/** JSON tree to plain Kotlin values (Map, List, String, Number, Boolean, null). */
internal fun fromJson(v: Any?): Any? = when (v) {
    null, JSONObject.NULL -> null
    is JSONObject -> v.toMap()
    is JSONArray -> (0 until v.length()).map { fromJson(v.opt(it)) }
    else -> v
}

internal fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { fromJson(opt(it)) }

/** Plain Kotlin values to a JSON tree. */
internal fun toJson(v: Any?): Any = when (v) {
    null -> JSONObject.NULL
    is JSONObject, is JSONArray -> v
    is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJson(x)) } }
    is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
    is Array<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
    // org.json throws on NaN / Infinity; JSON.stringify on web writes null.
    is Double -> if (v.isFinite()) v else JSONObject.NULL
    is Float -> if (v.isFinite()) v.toDouble() else JSONObject.NULL
    is Number, is String, is Boolean -> v
    else -> v.toString()
}

/** `===` as JavaScript sees JSON values: numbers compare by value whatever their Kotlin type. */
internal fun jsEquals(a: Any?, b: Any?): Boolean =
    if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b

/** `String(x)` as JavaScript prints it (1.0 prints as "1"). */
internal fun jsString(v: Any?): String = when (v) {
    null -> "null"
    is Double -> if (v % 1.0 == 0.0 && !v.isInfinite()) v.toLong().toString() else v.toString()
    is Float -> jsString(v.toDouble())
    else -> v.toString()
}
