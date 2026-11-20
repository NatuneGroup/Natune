
package app.natune.constants

fun String.toPlaylistTagOrder(availableTagIds: List<String>): List<String> {
    val availableIds = availableTagIds.toSet()
    val savedOrder =
        split(",")
            .map(String::trim)
            .filter { it.isNotEmpty() && it in availableIds }
            .distinct()

    return savedOrder + availableTagIds.filterNot(savedOrder::contains)
}

fun List<String>.toPlaylistTagPreference(): String =
    distinct().joinToString(",")
