package app.local1st.files.core.fs.priv

enum class TransportPref(val storedValue: String) {
    AUTO("auto"),
    SU("su"),
    SHIZUKU("shizuku"),
    OFF("off"),
    ;

    companion object {
        fun fromStoredValue(value: String): TransportPref =
            entries.firstOrNull { it.storedValue == value } ?: AUTO
    }
}
