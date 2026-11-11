package kami.libs.press

enum class Dye(val id: String, val item: String) {
    WHITE("white"), ORANGE("orange"), MAGENTA("magenta"), LIGHT_BLUE("light_blue"), YELLOW("yellow"), LIME("lime"), PINK("pink"), GRAY("gray"),
    LIGHT_GRAY("light_gray"), CYAN("cyan"), PURPLE("purple"), BLUE("blue"), BROWN("brown"), GREEN("green"), RED("red"), BLACK("black");

    constructor(id: String) : this(id, "minecraft:${id}_dye")

    val free: Boolean get() = this == WHITE || this == BLACK

    companion object {
        fun byId(id: String): Dye? = entries.firstOrNull { it.id == id.lowercase() }
    }
}

sealed interface Ref {
    val kind: String
    val id: String
    val token: String get() = "{$kind:$id}"

    data class Price(val item: String) : Ref {
        override val kind get() = "price"
        override val id get() = item
    }

    data class Flag(val country: String) : Ref {
        override val kind get() = "flag"
        override val id get() = country
    }

    data class Head(val player: String) : Ref {
        override val kind get() = "head"
        override val id get() = player
    }

    data class Item(override val id: String) : Ref {
        override val kind get() = "item"
    }

    data class Icon(val name: String) : Ref {
        override val kind get() = "icon"
        override val id get() = name
    }
}

data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val color: Dye? = null, val ref: Ref? = null)

sealed interface Block {
    data class Heading(val level: Int, val spans: List<Span>) : Block
    data class Para(val spans: List<Span>, val centered: Boolean = false) : Block
    data class Quote(val spans: List<Span>) : Block
    data class Bullets(val items: List<List<Span>>) : Block
    data object Rule : Block
}

data class Doc(val blocks: List<Block>)

internal fun Block.lines(): List<List<Span>> = when (this) {
    is Block.Heading -> listOf(spans)
    is Block.Para -> listOf(spans)
    is Block.Quote -> listOf(spans)
    is Block.Bullets -> items
    Block.Rule -> emptyList()
}
