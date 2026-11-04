package io.github.localledger.app

/** Keeps UI tests independent of public update servers without changing the English configuration. */
class EnglishTestApplication : LedgerApplication() {
    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("automatic_updates", false).commit()
    }
}
