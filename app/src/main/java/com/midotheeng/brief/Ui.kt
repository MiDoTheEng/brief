package com.midotheeng.brief

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF141821)
val Muted = Color(0xFF606875)
val Paper = Color(0xFFF6F4EF)
val Card = Color(0xFFFFFFFF)
val Accent = Color(0xFFE2572B)
val AccentSoft = Color(0xFFFBE4DA)
val Link = Color(0xFF1E5AA8)

/** Two-option switch, e.g. Short | Detailed. */
@Composable
fun Toggle(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFE9E5DC)).padding(3.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (on) Ink else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color.White else Ink, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp)
            }
        }
    }
}

val LENGTHS = listOf(Prefs.SHORT to "Short", Prefs.DETAILED to "Detailed")
val LANGS = listOf(Prefs.EN to "English", Prefs.AR to "العربية")

private val STAMP = Regex("""\[(\d{1,2}(?::\d{2}){1,2})]""")

private fun seconds(stamp: String): Long = stamp.split(':').fold(0L) { acc, p -> acc * 60 + (p.toLongOrNull() ?: 0) }

/** For YouTube links, a timestamp opens the video at that moment. */
private fun jumpUrl(link: String, stamp: String): String? {
    if (!YouTube.matches(link)) return null
    val base = link.replace(Regex("""[?&]t=\d+s?"""), "")
    return base + (if (base.contains('?')) "&" else "?") + "t=" + seconds(stamp)
}

private fun inline(s: String, link: String): AnnotatedString = buildAnnotatedString {
    Md.runs(s).forEach { (t, bold) ->
        val start = length
        var i = 0
        STAMP.findAll(t).forEach { m ->
            append(t.substring(i, m.range.first))
            val url = jumpUrl(link, m.groupValues[1])
            if (url != null) {
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = Link, fontWeight = FontWeight.Medium)))) { append(m.value) }
            } else withStyle(SpanStyle(color = Muted, fontSize = 13.sp)) { append(m.value) }
            i = m.range.last + 1
        }
        append(t.substring(i))
        if (bold) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
    }
}

/** Shows the brief's Markdown nicely; Arabic flows right-to-left automatically. */
@Composable
fun MarkdownView(md: String, link: String) {
    val base = TextStyle(color = Ink, fontSize = 16.sp, lineHeight = 24.sp, textDirection = TextDirection.Content)
    val rtl = md.count { it in '\u0600'..'\u06FF' } * 4 > md.count { it.isLetter() }
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) { Column {
        Md.parse(md).forEach { b ->
            when (b.t) {
                Md.T.H1 -> Text(inline(b.text, link), style = base.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 8.dp))
                Md.T.H2 -> Text(inline(b.text, link), style = base.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Accent), modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                Md.T.H3 -> Text(inline(b.text, link), style = base.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                Md.T.BULLET -> Row(Modifier.padding(start = (b.indent * 16).dp, top = 3.dp, bottom = 3.dp)) {
                    Text("•", style = base.copy(color = Accent, fontWeight = FontWeight.Bold), modifier = Modifier.padding(horizontal = 6.dp))
                    Text(inline(b.text, link), style = base, modifier = Modifier.weight(1f))
                }
                Md.T.PARA -> Text(inline(b.text, link), style = base, modifier = Modifier.padding(vertical = 4.dp))
                Md.T.BLANK -> Spacer(Modifier.height(4.dp))
            }
        }
    } }
}
