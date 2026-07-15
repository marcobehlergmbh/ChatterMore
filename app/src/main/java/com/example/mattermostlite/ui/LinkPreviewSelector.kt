package com.example.mattermostlite.ui

import com.example.mattermostlite.model.LinkPreview

internal object LinkPreviewSelector {
    fun firstGeneric(previews: List<LinkPreview>): LinkPreview? = previews.firstOrNull { preview ->
        YouTubeLinkParser.fromUrl(preview.url) == null &&
            SunoLinkParser.fromUrl(preview.url) == null
    }
}
