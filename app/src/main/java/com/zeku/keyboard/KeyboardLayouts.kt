package com.zeku.keyboard

enum class KeyType {
    CHARACTER,
    SPACE,
    SHIFT,
    DELETE,
    ENTER,
    TO_SYMBOLS,
    TO_SYMBOLS_2,
    TO_LETTERS,
    SPACER
}

enum class LayoutMode { LETTERS, SYMBOLS_1, SYMBOLS_2 }

/**
 * Satu tombol. [weight] = lebar relatif dalam satu baris (satu baris berjumlah 10).
 */
class Key(
    val label: String,
    val type: KeyType = KeyType.CHARACTER,
    val weight: Float = 1f
)

/**
 * Tata letak keyboard: semua layar punya 5 baris supaya tinggi keyboard tetap sama.
 * Baris atas selalu angka 1-0.
 */
object KeyboardLayouts {

    fun get(mode: LayoutMode): List<List<Key>> = when (mode) {
        LayoutMode.LETTERS -> LETTERS
        LayoutMode.SYMBOLS_1 -> SYMBOLS_1
        LayoutMode.SYMBOLS_2 -> SYMBOLS_2
    }

    private fun chars(text: String): List<Key> = text.map { Key(it.toString()) }

    private fun spacer(): Key = Key("", KeyType.SPACER, 0.5f)

    private fun bottomRow(switchKey: Key): List<Key> = listOf(
        switchKey,
        Key(","),
        Key(" ", KeyType.SPACE, 5f),
        Key("."),
        Key("", KeyType.ENTER, 1.5f)
    )

    private val NUMBERS: List<Key> = chars("1234567890")

    private val LETTERS: List<List<Key>> = listOf(
        NUMBERS,
        chars("qwertyuiop"),
        listOf(spacer()) + chars("asdfghjkl") + spacer(),
        listOf(Key("", KeyType.SHIFT, 1.5f)) + chars("zxcvbnm") + Key("", KeyType.DELETE, 1.5f),
        bottomRow(Key("123", KeyType.TO_SYMBOLS, 1.5f))
    )

    private val SYMBOLS_1: List<List<Key>> = listOf(
        NUMBERS,
        chars("@#\$_&-+()/"),
        listOf(spacer()) + chars("%=^[]{}<>") + spacer(),
        listOf(Key("=\\<", KeyType.TO_SYMBOLS_2, 1.5f)) + chars("*\"':;!?") + Key("", KeyType.DELETE, 1.5f),
        bottomRow(Key("ABC", KeyType.TO_LETTERS, 1.5f))
    )

    private val SYMBOLS_2: List<List<Key>> = listOf(
        NUMBERS,
        chars("~`|•√π÷×¶∆"),
        listOf(spacer()) + chars("£¢€¥°©®™\\") + spacer(),
        listOf(Key("123", KeyType.TO_SYMBOLS, 1.5f)) + chars("¡¿«»“”’") + Key("", KeyType.DELETE, 1.5f),
        bottomRow(Key("ABC", KeyType.TO_LETTERS, 1.5f))
    )
}
