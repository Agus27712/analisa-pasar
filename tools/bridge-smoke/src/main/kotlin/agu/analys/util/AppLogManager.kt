package agu.analys.util
object AppLogManager {
    fun info(tag: String, message: String) = println("$tag: $message")
    fun error(tag: String, message: String, error: Throwable) { System.err.println("$tag: $message"); error.printStackTrace() }
}
