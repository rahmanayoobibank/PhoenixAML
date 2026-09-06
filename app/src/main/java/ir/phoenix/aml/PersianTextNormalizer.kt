package ir.phoenix.aml

object PersianTextNormalizer {

    fun normalize(text: String): String {
        return text
            .replace('\u0000'.toString(), "")
            .replace('\u064A', '\u06CC')
            .replace('\u0649', '\u06CC')
            .replace('\u0643', '\u06A9')
            .replace('\u200C'.toString(), " ")
            .replace('\u200D'.toString(), "")
            .replace('\u200E'.toString(), "")
            .replace('\u200F'.toString(), "")
            .replace('\u202A'.toString(), "")
            .replace('\u202B'.toString(), "")
            .replace('\u202C'.toString(), "")
            .replace('\u202D'.toString(), "")
            .replace('\u202E'.toString(), "")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
