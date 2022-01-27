package org.gotson.komga.interfaces.api.rest.dto

import org.gotson.komga.domain.model.PageHashMatch
import org.gotson.komga.infrastructure.web.toFilePath

data class PageHashMatchDto(
  val bookId: String,
  val url: String,
  val pageNumber: Int,
)

fun PageHashMatch.toDto() =
  PageHashMatchDto(
    bookId = bookId,
    url = url.toFilePath(),
    pageNumber = pageNumber,
  )