package com.marcobehler.chattermore.ui

import com.marcobehler.chattermore.model.LinkPreview

internal object LinkPreviewSelector {
    fun firstGeneric(previews: List<LinkPreview>): LinkPreview? = previews.firstOrNull { preview ->
        YouTubeLinkParser.fromUrl(preview.url) == null &&
            SunoLinkParser.fromUrl(preview.url) == null
    }
}
