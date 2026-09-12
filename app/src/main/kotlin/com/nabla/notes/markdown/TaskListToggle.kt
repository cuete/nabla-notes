package com.nabla.notes.markdown

import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.SpannableBuilder
import io.noties.markwon.ext.tasklist.TaskListItem
import io.noties.markwon.ext.tasklist.TaskListProps
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Block
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import java.util.concurrent.atomic.AtomicInteger

/**
 * Matches a single task-list checkbox line: bare "[ ] text" / "[x] text" (the app's own syntax,
 * see [normalizeBareTaskLines]) as well as GFM list-item form "- [ ] text" / "* [x] text" /
 * "+ [X] text" (kept for notes written before bare syntax was supported). Ordinals assigned by
 * [taskListTogglePlugin] (one per TaskListItem node, in document order) line up 1:1 with matches
 * of this regex over the same source text.
 */
private val TASK_ITEM_REGEX = Regex("""(^\s*(?:[-*+]\s+)?\[)([ xX])(\])""", RegexOption.MULTILINE)

/**
 * Matches a bare checkbox line ("[ ] text" / "[x] text") that has no GFM list marker. Only the
 * leading whitespace may precede the bracket — a line already starting "- [ ]" etc. won't match,
 * since "-" isn't whitespace.
 */
private val BARE_TASK_LINE_REGEX = Regex("""^(\s*)\[([ xX])\]""", RegexOption.MULTILINE)

/**
 * Rewrites bare checkbox lines into GFM list-item syntax ("- [ ] text") so Markwon's
 * TaskListPlugin — which, per the GFM task-list spec, only recognizes checkboxes inside a real
 * list item — renders them. Call this on the text passed to [Markwon.setMarkdown][io.noties.markwon.Markwon.setMarkdown];
 * leave the stored/edited note text in its original bare form (that's what [TASK_ITEM_REGEX],
 * [countTaskItems] and [flipTaskCheckbox] operate on, and what the checkbox toolbar action
 * inserts).
 */
fun normalizeBareTaskLines(text: String): String =
    BARE_TASK_LINE_REGEX.replace(text) { "${it.groupValues[1]}- [${it.groupValues[2]}]" }

/** Number of task-list checkbox lines in [text]. Used to compute per-segment ordinal offsets. */
fun countTaskItems(text: String): Int = TASK_ITEM_REGEX.findAll(text).count()

/**
 * Flips the [ordinal]-th checkbox (0-indexed, document order) in [text] between "[ ]" and "[x]".
 * Returns [text] unchanged if [ordinal] is out of range.
 */
fun flipTaskCheckbox(text: String, ordinal: Int): String {
    val match = TASK_ITEM_REGEX.findAll(text).elementAtOrNull(ordinal) ?: return text
    val stateGroup = match.groups[2] ?: return text
    val newState = if (stateGroup.value.equals("x", ignoreCase = true)) " " else "x"
    return text.substring(0, stateGroup.range.first) + newState + text.substring(stateGroup.range.last + 1)
}

/**
 * Markwon plugin that makes rendered task-list items tappable. Tapping a checkbox item's own
 * text (not its nested sub-items) invokes [onToggle] with that item's ordinal position among
 * all checkbox lines in the source (see [TASK_ITEM_REGEX] / [countTaskItems]).
 *
 * [counter] is exposed so callers rendering multiple independent documents/segments through one
 * shared Markwon instance can reset/offset it to match ordinal position in the full source text.
 */
fun taskListTogglePlugin(
    counter: AtomicInteger,
    onToggle: (ordinal: Int) -> Unit
): AbstractMarkwonPlugin = object : AbstractMarkwonPlugin() {
    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(TaskListItem::class.java) { visitor, node ->
            val ordinal = counter.getAndIncrement()
            val length = visitor.length()

            visitor.visitChildren(node)

            TaskListProps.DONE.set(visitor.renderProps(), node.isDone)

            val spans = visitor.configuration()
                .spansFactory()
                .get(TaskListItem::class.java)
                ?.getSpans(visitor.configuration(), visitor.renderProps())

            val contentLength = TaskItemOwnTextVisitor.contentLength(node)
            if (spans != null && contentLength > 0) {
                val itemEnd = length + contentLength
                // Nested markdown (e.g. a [text](url) link) already set its own ClickableSpan
                // via visitChildren above. Skip those ranges so the toggle span, added on top,
                // doesn't shadow the link's own tap target with LinkMovementMethod picking the
                // first ClickableSpan it finds at a given offset.
                val linkRanges = visitor.builder()
                    .getSpans(length, itemEnd)
                    .filter { it.what is ClickableSpan }
                    .map { it.start to it.end }
                    .sortedBy { it.first }

                var cursor = length
                for ((linkStart, linkEnd) in linkRanges) {
                    if (linkStart > cursor) {
                        setToggleSpan(visitor, cursor, linkStart, ordinal, onToggle)
                    }
                    cursor = maxOf(cursor, linkEnd)
                }
                if (cursor < itemEnd) {
                    setToggleSpan(visitor, cursor, itemEnd, ordinal, onToggle)
                }
            }

            if (spans != null) {
                SpannableBuilder.setSpans(visitor.builder(), spans, length, visitor.length())
            }

            if (visitor.hasNext(node)) {
                visitor.ensureNewLine()
            }
        }
    }
}

private fun setToggleSpan(
    visitor: MarkwonVisitor,
    start: Int,
    end: Int,
    ordinal: Int,
    onToggle: (ordinal: Int) -> Unit
) {
    visitor.builder().setSpan(
        object : ClickableSpan() {
            override fun onClick(widget: View) = onToggle(ordinal)
            // No-op: this isn't a hyperlink, keep the normal text styling.
            override fun updateDrawState(ds: TextPaint) {}
        },
        start,
        end
    )
}

/** Rendered text length of a TaskListItem's own paragraph content, excluding nested sub-items. */
private class TaskItemOwnTextVisitor : AbstractVisitor() {
    var contentLength: Int = 0
        private set

    override fun visit(text: Text) {
        contentLength += text.literal.length
    }

    override fun visit(softLineBreak: SoftLineBreak) {
        contentLength += 1
    }

    override fun visit(hardLineBreak: HardLineBreak) {
        contentLength += 1
    }

    override fun visitChildren(parent: Node) {
        var node = parent.firstChild
        while (node != null) {
            val next = node.next
            if (node is Block && node !is Paragraph) break
            node.accept(this)
            node = next
        }
    }

    companion object {
        fun contentLength(node: Node): Int {
            val visitor = TaskItemOwnTextVisitor()
            visitor.visitChildren(node)
            return visitor.contentLength
        }
    }
}
