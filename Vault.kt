package app.browservault.vault

import android.content.Context

/** Egy folyamaton belüli közös tároló: a szolgáltatás és a felület ugyanazt látja. */
object Vault {

    @Volatile
    private var storeRef: VaultStore? = null

    fun store(context: Context): VaultStore {
        val existing = storeRef
        if (existing != null) return existing
        synchronized(this) {
            val again = storeRef
            if (again != null) return again
            val created = VaultStore(context.applicationContext)
            storeRef = created
            return created
        }
    }

    fun config(context: Context): VaultConfig = VaultConfig(context.applicationContext)
}
