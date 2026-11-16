package com.zeku.keyboard

/** Satu tab kategori di panel emoji: ikon tab dan isinya. */
class EmojiCategory(val icon: String, val items: List<String>)

/**
 * Daftar emoji per kategori. Emoji yang tidak punya gambar di font perangkat disaring saat
 * panel dibuka (lihat ZekuKeyboardView), jadi aman memuat emoji yang baru di Android lama.
 */
object EmojiData {

    private fun list(text: String): List<String> =
        text.trim().split(' ', '\n').filter { it.isNotEmpty() }

    val categories: List<EmojiCategory> = listOf(
        EmojiCategory(
            "😀",
            list(
                """
                😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔
                🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓
                🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿
                💀 💩 🤡 👹 👺 👻 👽 👾 🤖 🙈 🙉 🙊
                """
            )
        ),
        EmojiCategory(
            "👍",
            list(
                """
                👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🤝 🙏 ✍️ 💪 👏 🙌 👐 🤲 🙋 🙆 🙅 🤷
                🤦 💁 🙇 👶 👦 👧 👨 👩 🧓 👴 👵 👮 👷 💂 🕵️ 👸 🤴 🎅 🤶 🦸 🦹 🧙 🧚 🧛 🧜 🧞 🧟 👼
                """
            )
        ),
        EmojiCategory(
            "❤️",
            list(
                """
                ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☪️ 🕉️ ☯️ ✨ 🌟 ⭐ 💫 ⚡ 🔥
                💥 💢 💯 ✅ ❌ ❎ ❓ ❗ ‼️ ⭕ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ ♻️ ➕ ➖ ➗ ✖️ ♾️ ™️ ©️ ®️
                """
            )
        ),
        EmojiCategory(
            "🐶",
            list(
                """
                🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋
                🐌 🐞 🐜 🕷️ 🦂 🐢 🐍 🦎 🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🐘 🦏 🐪 🐫 🦒 🐃
                🐂 🐄 🐎 🐖 🐏 🐑 🐐 🦌 🐕 🐩 🐈 🐓 🦃 🕊️ 🐇 🐁 🐀 🐿️ 🌵 🎄 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🍁 🍂 🍃
                🌺 🌻 🌹 🌷 🌼 🌸 💐 🍄 🌍 🌙 ☀️ ⛅ ☁️ 🌧️ ⛈️ ❄️ ☃️ 🌈 💧 🌊
                """
            )
        ),
        EmojiCategory(
            "🍔",
            list(
                """
                🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥒 🌶️ 🌽 🥕 🥔 🍠 🥐 🍞 🥖 🧀
                🥚 🍳 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🍤 🍙 🍚 🍘 🍥 🍢 🍡 🍧 🍨 🍦
                🥧 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🥜 🍯 🥛 ☕ 🍵 🥤 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🧃
                """
            )
        ),
        EmojiCategory(
            "⚽",
            list(
                """
                ⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🎱 🏓 🏸 🥅 🏒 🏑 🏏 ⛳ 🏹 🎣 🥊 🥋 🎽 ⛸️ 🛹 🎿 ⛷️ 🏂 🏋️ 🤸 🤺 🏇
                🧘 🏄 🏊 🚴 🏆 🥇 🥈 🥉 🏅 🎖️ 🎫 🎪 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 🎯 🎳 🎮 🎰 🧩
                """
            )
        ),
        EmojiCategory(
            "🚗",
            list(
                """
                🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛵 🏍️ 🚲 🛴 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄
                🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🚀 🛸 🚁 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓ ⛽ 🚧 🚦 🚥 🗺️ 🗿 🗽 🗼 🏰 🏯
                🏟️ 🎡 🎢 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🏠 🏡 🏢 🏬 🏥 🏦 🏨 🏪 🏫 🏭 ⛪ 🕌 🕍 🌃
                🌆 🌇 🌉
                """
            )
        ),
        EmojiCategory(
            "💡",
            list(
                """
                ⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💾 💿 📷 📸 📹 🎥 📞 ☎️ 📺 📻 🎙️ ⏰ ⏳ 📡 🔋 🔌 💡 🔦 🕯️ 💸 💵 💴
                💶 💷 💰 💳 💎 ⚖️ 🔧 🔨 ⚒️ 🛠️ ⛏️ 🔩 ⚙️ 🧰 🧲 🔫 💣 🔪 🗡️ 🛡️ 🚬 ⚰️ 🔮 📿 💈 🔭 🔬 💊 💉
                🌡️ 🧬 🔑 🗝️ 🚪 🛏️ 🛋️ 🚽 🚿 🛁 🧴 🧷 🧹 🧺 🧻 🧼 🧽 🛒 🎁 🎈 🎀 🎉 🎊 ✉️ 📦 📝 📖 📚 📎
                📌 ✂️ 🔒 🔓
                """
            )
        )
    )
}
