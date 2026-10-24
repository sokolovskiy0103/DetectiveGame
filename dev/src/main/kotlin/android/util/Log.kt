package android.util

object Log {
    private fun p(l: String, tag: String, msg: String, t: Throwable?) {
        println("[$l/$tag] $msg"); t?.printStackTrace()
    }
    @JvmStatic fun d(tag: String, msg: String) = p("D", tag, msg, null)
    @JvmStatic fun i(tag: String, msg: String) = p("I", tag, msg, null)
    @JvmStatic fun w(tag: String, msg: String) = p("W", tag, msg, null)
    @JvmStatic fun w(tag: String, msg: String, t: Throwable) = p("W", tag, msg, t)
    @JvmStatic fun e(tag: String, msg: String) = p("E", tag, msg, null)
    @JvmStatic fun e(tag: String, msg: String, t: Throwable) = p("E", tag, msg, t)
}
