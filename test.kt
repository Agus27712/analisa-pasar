const val GEMINI_MODEL = "gemini-2.0-flash"
const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
class Test {
    fun test() {
        println(GEMINI_URL)
    }
}
