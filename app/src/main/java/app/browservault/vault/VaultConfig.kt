package app.browservault.vault

import android.content.Context
import java.security.SecureRandom

/** Port és hozzáférési kulcs — az első indításkor generálódik. */
class VaultConfig(context: Context) {

    private val prefs = context.getSharedPreferences("browservault", Context.MODE_PRIVATE)

    val port: Int
        get() = prefs.getInt("port", DEFAULT_PORT)

    val token: String
        get() {
            val saved = prefs.getString("token", null)
            if (!saved.isNullOrEmpty()) return saved
            return regenerate()
        }

    fun regenerate(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val builder = StringBuilder()
        for (byte in bytes) builder.append(String.format("%02x", byte.toInt() and 0xff))
        val fresh = builder.toString()
        prefs.edit().putString("token", fresh).apply()
        return fresh
    }

    companion object {
        const val DEFAULT_PORT = 8787
    }
}
