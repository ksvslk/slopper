package com.indrek.slopper.service

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.*
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.indrek.slopper.MyApplication
import com.indrek.slopper.R

class ContextAutofillService : AutofillService() {

    companion object { const val TAG = "AutofillService" }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val structure = request.fillContexts.last().structure
        val focusedNode = findFocusedInputNode(structure)
        val focusedId = focusedNode?.autofillId
        if (focusedId == null) { callback.onSuccess(null); return }

        val replies = MyApplication.cachedReplies
        if (replies != null && replies.isNotEmpty()) {
            val response = buildMultiReplyResponse(focusedId, replies)
            callback.onSuccess(response)
            MyApplication.cachedReplies = null
        } else {
            callback.onSuccess(null)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }

    /**
     * This is the updated presentation logic for the minimalistic text bubbles.
     */
    private fun buildMultiReplyResponse(focusedId: AutofillId, replies: List<Pair<String, String>>): FillResponse {
        val builder = FillResponse.Builder()

        replies.forEach { (tone, text) ->
            // Use our new minimalistic layout (which is just a TextView)
            val presentation = RemoteViews(packageName, R.layout.autofill_suggestion).apply {
                // We only need to set the text now.
                // The [tone] prefix is optional but helpful to remind the user.
                setTextViewText(R.id.suggestion_text, "[$tone] $text")
            }

            val dataset = Dataset.Builder(presentation)
                .setValue(focusedId, AutofillValue.forText(text))
                .build()

            builder.addDataset(dataset)
        }

        return builder.build()
    }

    // The getIconForTone() function has been completely removed as it is no longer needed.

    private fun findFocusedInputNode(structure: AssistStructure): AssistStructure.ViewNode? {
        for (i in 0 until structure.windowNodeCount) {
            val windowNode = structure.getWindowNodeAt(i)
            val found = findFocusedInputNodeRecursive(windowNode.rootViewNode)
            if (found != null) return found
        }
        return null
    }

    private fun findFocusedInputNodeRecursive(node: AssistStructure.ViewNode): AssistStructure.ViewNode? {
        if (node.isFocused && node.autofillId != null) return node
        for (i in 0 until node.childCount) {
            val found = findFocusedInputNodeRecursive(node.getChildAt(i))
            if (found != null) return found
        }
        return null
    }
}