package dev.braincore.boss

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

// A small GitHub-flavoured markdown renderer: enough for issue bodies, logs and cards.

sealed interface Block
data class Heading(val level: Int, val text: String) : Block
data class Para(val text: String) : Block
data class Item(val indent: Int, val marker: String, val text: String) : Block
data class Quote(val text: String) : Block
data class Code(val text: String) : Block
data class Img(val url: String, val alt: String) : Block
data object Rule : Block

private val IMG = Regex("!\\[([^\\]]*)]\\(\\s*<?([^)\\s>]+)>?[^)]*\\)")
private val ITEM = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")

fun parseBlocks(md: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()

    // Text first, then the images it carried, each on its own row (so an option's sample sits under its bullet).
    fun add(text: String, make: (String) -> Block) {
        val stripped = IMG.replace(text, "").trim()
        if (stripped.isNotEmpty()) out += make(stripped)
        IMG.findAll(text).forEach { out += Img(it.groupValues[2], it.groupValues[1]) }
    }
    fun flush() {
        if (para.isNotBlank()) add(para.toString().trim(), ::Para)
        para.clear()
    }

    var code: StringBuilder? = null
    var table: StringBuilder? = null
    for (raw in md.replace(Regex("(?s)<!--.*?-->"), "").lines()) {
        val t = raw.trim()
        if (code != null) {
            if (t.startsWith("```")) { out += Code(code.toString().trimEnd()); code = null } else code.appendLine(raw)
            continue
        }
        if (table != null && !t.startsWith("|")) { out += Code(table.toString().trimEnd()); table = null }
        val item = ITEM.find(raw)
        val heading = HEADING.find(t)
        when {
            t.startsWith("```") -> { flush(); code = StringBuilder() }
            t.isEmpty() -> flush()
            t.startsWith("|") -> {
                flush()
                if (!Regex("^\\|[\\s:|-]+\\|?$").matches(t)) (table ?: StringBuilder().also { table = it }).appendLine(t)
            }
            heading != null -> { flush(); add(heading.groupValues[2]) { Heading(heading.groupValues[1].length, it) } }
            t.replace(" ", "").let { it.length >= 3 && (it.all { c -> c == '-' } || it.all { c -> c == '*' } || it.all { c -> c == '_' }) } ->
                { flush(); out += Rule }
            item != null -> {
                flush()
                val indent = item.groupValues[1].replace("\t", "  ").length / 2
                add(item.groupValues[3]) { Item(indent, item.groupValues[2], it) }
            }
            t.startsWith(">") -> {
                flush()
                val text = t.removePrefix(">").trim()
                val last = out.lastOrNull()
                if (last is Quote) out[out.size - 1] = Quote(last.text + "\n" + text) else out += Quote(text)
            }
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(t) }
        }
    }
    flush()
    code?.let { out += Code(it.toString().trimEnd()) }
    table?.let { out += Code(it.toString().trimEnd()) }
    return out
}

private val INLINE = Regex(
    "\\*\\*(.+?)\\*\\*" +                          // 1 bold
        "|`([^`]+)`" +                              // 2 code
        "|\\[([^\\]]+)]\\(\\s*<?([^)\\s>]+)>?[^)]*\\)" + // 3 text, 4 url
        "|(https?://[^\\s)<>]+)" +                  // 5 bare url
        "|(?<![\\w*])[*_]([^*_\\s](?:[^*_]*[^*_\\s])?)[*_](?![\\w*])" // 6 italic
)

fun inline(text: String, link: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    val linkStyle = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
    var i = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(i, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(g[1], link, codeBg)) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(g[2]) }
            g[3].isNotEmpty() -> withLink(LinkAnnotation.Url(g[4], linkStyle)) { append(g[3]) }
            g[5].isNotEmpty() -> withLink(LinkAnnotation.Url(g[5], linkStyle)) { append(shortUrl(g[5])) }
            g[6].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[6]) }
        }
        i = m.range.last + 1
    }
    append(text.substring(i))
}

private fun shortUrl(url: String) = url.removePrefix("https://").removePrefix("http://").let {
    if (it.length > 48) it.take(45) + "…" else it
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    val link = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val ty = MaterialTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) when (b) {
            is Heading -> Text(
                inline(b.text, link, codeBg),
                style = when (b.level) { 1 -> ty.titleLarge; 2 -> ty.titleMedium; else -> ty.titleSmall },
                modifier = Modifier.padding(top = 6.dp),
            )
            is Para -> Text(inline(b.text, link, codeBg), style = ty.bodyMedium)
            is Item -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
                Text(if (b.marker.first().isDigit()) b.marker else "•", style = ty.bodyMedium, modifier = Modifier.width(22.dp))
                Text(inline(b.text, link, codeBg), style = ty.bodyMedium)
            }
            is Quote -> Surface(color = codeBg, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
                Text(inline(b.text, link, codeBg), style = ty.bodyMedium, fontStyle = FontStyle.Italic, modifier = Modifier.padding(8.dp))
            }
            is Code -> Surface(color = codeBg, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    b.text, style = ty.bodySmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                )
            }
            is Img -> Picture(b.url, b.alt)
            Rule -> HorizontalDivider()
        }
    }
}

/** An image that opens full screen on tap (samples on a card are meant to be looked at closely). */
@Composable
fun Picture(url: String, alt: String) {
    var zoom by remember { mutableStateOf(false) }
    AsyncImage(
        model = url, contentDescription = alt, contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { zoom = true },
    )
    if (zoom) Dialog(onDismissRequest = { zoom = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize().clickable { zoom = false }) {
            AsyncImage(model = url, contentDescription = alt, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}
