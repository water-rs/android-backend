package dev.waterui.android.ffi

/**
 * Bringing up the inspector from the Android runtime.
 *
 * A debug build listens on a port and publishes where it is, so all the native
 * side has to do is ask. The inspector itself runs on the developer's computer,
 * which is why [open] reports an endpoint rather than launching a window here.
 *
 * Every call is inert unless an endpoint is running, which in practice means a
 * debug build.
 */
object InspectorJni {
    init {
        // All JNI exports are provided by Rust.
        System.loadLibrary("waterui_app")
    }

    /**
     * Whether this build offers inspection at all.
     *
     * Asked before showing "Inspect element", so a release build shows nothing
     * rather than an entry that does nothing.
     */
    @JvmStatic external fun isAvailable(envPtr: Long): Boolean

    /** Opens the inspector on this application, revealing nothing in particular. */
    @JvmStatic external fun open(envPtr: Long)

    /** Reveals one accessibility node in the inspector. */
    @JvmStatic external fun inspectNode(envPtr: Long, node: Long)

    /**
     * Whether anything is watching the accessibility tree.
     *
     * The view hierarchy is walked only when the answer is yes, so an
     * application with no inspector attached pays nothing for the channel.
     */
    @JvmStatic external fun wantsTree(envPtr: Long): Boolean
}
