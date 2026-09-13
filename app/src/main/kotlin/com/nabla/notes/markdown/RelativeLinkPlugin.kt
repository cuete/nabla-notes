package com.nabla.notes.markdown

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import com.nabla.notes.model.FileKind
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolver
import io.noties.markwon.MarkwonConfiguration

private const val SCHEME = "nablanote"

/** A markdown link resolved (at render time) to a specific OneDrive item. */
data class NablaLink(val id: String, val kind: FileKind, val name: String)

fun buildNablaLinkUri(id: String, kind: FileKind, name: String): String =
    "$SCHEME://open?id=${Uri.encode(id)}&kind=${kind.name}&name=${Uri.encode(name)}"

fun parseNablaLink(link: String): NablaLink? {
    val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return null
    if (uri.scheme != SCHEME) return null
    val id = uri.getQueryParameter("id") ?: return null
    val kindName = uri.getQueryParameter("kind") ?: return null
    val kind = runCatching { FileKind.valueOf(kindName) }.getOrNull() ?: return null
    val name = uri.getQueryParameter("name") ?: ""
    return NablaLink(id, kind, name)
}

/**
 * Markwon plugin that intercepts taps on our synthetic `nablanote://` links (produced by
 * [resolveRelativeMediaLinks] for resolved relative paths) and dispatches them to
 * [onOpenResolvedLink] instead of letting Markwon launch them as external `ACTION_VIEW` intents.
 * Any other link falls through to normal external-link handling.
 */
fun relativeLinkResolverPlugin(onOpenResolvedLink: (NablaLink) -> Unit): AbstractMarkwonPlugin =
    object : AbstractMarkwonPlugin() {
        override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
            builder.linkResolver(
                LinkResolver { view, link ->
                    val nablaLink = parseNablaLink(link)
                    if (nablaLink != null) {
                        onOpenResolvedLink(nablaLink)
                    } else {
                        openExternalLink(view, link)
                    }
                }
            )
        }
    }

private fun openExternalLink(view: View, link: String) {
    try {
        view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(view.context, "No app found to open this link", Toast.LENGTH_SHORT).show()
    }
}
