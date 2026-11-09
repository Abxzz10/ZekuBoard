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
 * Pilihan tampilan layar huruf. Layar simbol tidak terpengaruh: angka, koma, dan titik
 * di sana selalu ada, supaya tetap bisa diketik walau disembunyikan di layar huruf.
 */
class LayoutOptions(
    val numberRow: Boolean = true,
    val comma: Boolean = true,
    val period: Boolean = true
)

/**
 * Tata letak keyboard. Layar simbol selalu 5 baris (baris atas angka 1-0).
 * Layar huruf 5 baris, atau 4 baris bila baris angka disembunyikan.
 */
object KeyboardLayouts {

    fun get(mode: LayoutMode, options: LayoutOptions = LayoutOptions()): List<List<Key>> =
        when (mode) {
            LayoutMode.LETTERS -> letters(options)
            LayoutMode.SYMBOLS_1 -> SYMBOLS_1
            LayoutMode.SYMBOLS_2 -> SYMBOLS_2
        }

    private fun chars(text: String): List<Key> = text.map { Key(it.toString()) }

    private fun spacer(): Key = Key("", KeyType.SPACER, 0.5f)

    /** Koma atau titik yang disembunyikan menyerahkan lebarnya ke tombol spasi. */
    private fun bottomRow(
        switchKey: Key,
        comma: Boolean = true,
        period: Boolean = true
    ): List<Key> {
        val row = ArrayList<Key>()
        row.add(switchKey)
        if (comma) row.add(Key(","))
        var spaceWeight = 5f
        if (!comma) spaceWeight += 1f
        if (!period) spaceWeight += 1f
        row.add(Key(" ", KeyType.SPACE, spaceWeight))
        if (period) row.add(Key("."))
        row.add(Key("", KeyType.ENTER, 1.5f))
        return row
    }

    private val NUMBERS: List<Key> = chars("1234567890")

    private fun letters(options: LayoutOptions): List<List<Key>> {
        val rows = ArrayList<List<Key>>()
        if (options.numberRow) rows.add(NUMBERS)
        rows.add(chars("qwertyuiop"))
        rows.add(listOf(spacer()) + chars("asdfghjkl") + spacer())
        rows.add(
            listOf(Key("", KeyType.SHIFT, 1.5f)) + chars("zxcvbnm") +
                Key("", KeyType.DELETE, 1.5f)
        )
        rows.add(
            bottomRow(
                Key("123", KeyType.TO_SYMBOLS, 1.5f),
                options.comma,
                options.period
            )
        )
        return rows
    }

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

/**
 * Karakter alternatif untuk tekan lama di layar huruf (mengikuti pola keyboard umum):
 * huruf Q-P menjadi angka, huruf lain menjadi simbol.
 */
object KeyAlternates {

    val NUMBER: Map<Char, String> = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0"
    )

    val SYMBOL: Map<Char, String> = mapOf(
        'a' to "@", 's' to "#", 'd' to "\$", 'f' to "_", 'g' to "&",
        'h' to "-", 'j' to "+", 'k' to "(", 'l' to ")",
        'z' to "*", 'x' to "\"", 'c' to "'", 'v' to ":", 'b' to ";",
        'n' to "!", 'm' to "?"
    )
}
