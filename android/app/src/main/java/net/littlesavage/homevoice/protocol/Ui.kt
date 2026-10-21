@file:OptIn(ExperimentalSerializationApi::class)

package net.littlesavage.homevoice.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Mirror of shared/src/ui.ts (UI schema v1). */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface UiMessage {
    val v: Int
    val id: String
    val title: String?
    val ttlMs: Long?

    @Serializable @SerialName("card")
    data class Card(
        override val v: Int = 1, override val id: String, override val title: String? = null, override val ttlMs: Long? = null,
        val body: String = "", val subtitle: String? = null, val imageUrl: String? = null, val tone: String? = null,
    ) : UiMessage

    @Serializable @SerialName("list")
    data class ListUi(
        override val v: Int = 1, override val id: String, override val title: String? = null, override val ttlMs: Long? = null,
        val items: List<Item> = emptyList(),
    ) : UiMessage {
        @Serializable data class Item(val title: String, val subtitle: String? = null, val trailing: String? = null)
    }

    @Serializable @SerialName("chart")
    data class Chart(
        override val v: Int = 1, override val id: String, override val title: String? = null, override val ttlMs: Long? = null,
        val chartType: String = "bar", val unit: String? = null, val series: List<Series> = emptyList(),
    ) : UiMessage {
        @Serializable data class Series(val label: String, val points: List<Point>)
        @Serializable data class Point(val x: String, val y: Double)
    }

    @Serializable @SerialName("status")
    data class Status(
        override val v: Int = 1, override val id: String, override val title: String? = null, override val ttlMs: Long? = null,
        val text: String = "", val tone: String? = null, val progress: Double? = null,
    ) : UiMessage
}
