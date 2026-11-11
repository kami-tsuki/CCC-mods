package kami.libs.ui.app

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Icon

private const val FORM_W = 300
private const val FORM_GAP = 4

fun formDialog(
    open: (Dialog) -> Unit, title: String, subtitle: String?, icon: Icon, primary: String,
    ready: () -> Boolean, reason: () -> String?, send: () -> Unit, width: Int = FORM_W, body: Ui.(Rect) -> Int
) = open(Dialog(title, subtitle, icon, width = width) { s ->
    s.used = body(s.body) + FORM_GAP
    dialogButtons(s, primary, ready(), reason()) {
        send()
        s.close()
    }
})
