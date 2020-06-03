package org.gotson.komga.infrastructure.jooq

import org.gotson.komga.domain.model.BookSearch
import org.gotson.komga.interfaces.rest.dto.AuthorDto
import org.gotson.komga.interfaces.rest.dto.BookDto
import org.gotson.komga.interfaces.rest.dto.BookMetadataDto
import org.gotson.komga.interfaces.rest.dto.MediaDto
import org.gotson.komga.interfaces.rest.dto.ReadProgressDto
import org.gotson.komga.interfaces.rest.persistence.BookDtoRepository
import org.gotson.komga.jooq.Tables
import org.gotson.komga.jooq.tables.records.BookMetadataRecord
import org.gotson.komga.jooq.tables.records.BookRecord
import org.gotson.komga.jooq.tables.records.MediaRecord
import org.gotson.komga.jooq.tables.records.ReadProgressRecord
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.ResultQuery
import org.jooq.impl.DSL
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Component
import java.net.URL

@Component
class BookDtoDao(
  private val dsl: DSLContext
) : BookDtoRepository {

  private val b = Tables.BOOK
  private val m = Tables.MEDIA
  private val d = Tables.BOOK_METADATA
  private val r = Tables.READ_PROGRESS
  private val a = Tables.BOOK_METADATA_AUTHOR

  private val mediaFields = m.fields().filterNot { it.name == m.THUMBNAIL.name }.toTypedArray()

  private val sorts = mapOf(
    "metadata.numberSort" to d.NUMBER_SORT,
    "createdDate" to b.CREATED_DATE,
    "lastModifiedDate" to b.LAST_MODIFIED_DATE,
    "fileSize" to b.FILE_SIZE
  )

  override fun findAll(search: BookSearch, userId: Long, pageable: Pageable): Page<BookDto> {
    val conditions = search.toCondition()

    val count = dsl.selectCount()
      .from(b)
      .leftJoin(m).on(b.ID.eq(m.BOOK_ID))
      .leftJoin(d).on(b.ID.eq(d.BOOK_ID))
      .where(conditions)
      .fetchOne(0, Long::class.java)

    val orderBy = pageable.sort.toOrderBy(sorts)

    val dtos = selectBase()
      .where(conditions)
      .and(readProgressCondition(userId))
      .orderBy(orderBy)
      .limit(pageable.pageSize)
      .offset(pageable.offset)
      .fetchAndMap()

    return PageImpl(
      dtos,
      PageRequest.of(pageable.pageNumber, pageable.pageSize, pageable.sort),
      count.toLong()
    )
  }

  override fun findByIdOrNull(bookId: Long, userId: Long): BookDto? =
    selectBase()
      .where(b.ID.eq(bookId))
      .and(readProgressCondition(userId))
      .fetchAndMap()
      .firstOrNull()

  override fun findPreviousInSeries(bookId: Long, userId: Long): BookDto? = findSibling(bookId, userId, next = false)

  override fun findNextInSeries(bookId: Long, userId: Long): BookDto? = findSibling(bookId, userId, next = true)

  private fun readProgressCondition(userId: Long): Condition = r.USER_ID.eq(userId).or(r.USER_ID.isNull)

  private fun findSibling(bookId: Long, userId: Long, next: Boolean): BookDto? {
    val record = dsl.select(b.SERIES_ID, d.NUMBER_SORT)
      .from(b)
      .leftJoin(d).on(b.ID.eq(d.BOOK_ID))
      .where(b.ID.eq(bookId))
      .fetchOne()
    val seriesId = record.get(0, Long::class.java)
    val numberSort = record.get(1, Float::class.java)

    return selectBase()
      .where(b.SERIES_ID.eq(seriesId))
      .and(readProgressCondition(userId))
      .orderBy(d.NUMBER_SORT.let { if (next) it.asc() else it.desc() })
      .seek(numberSort)
      .limit(1)
      .fetchAndMap()
      .firstOrNull()
  }

  private fun selectBase() =
    dsl.select(
      *b.fields(),
      *mediaFields,
      *d.fields(),
      *r.fields()
    ).from(b)
      .leftJoin(m).on(b.ID.eq(m.BOOK_ID))
      .leftJoin(d).on(b.ID.eq(d.BOOK_ID))
      .leftJoin(r).on(b.ID.eq(r.BOOK_ID))

  private fun ResultQuery<Record>.fetchAndMap() =
    fetch()
      .map { rec ->
        val br = rec.into(b)
        val mr = rec.into(m)
        val dr = rec.into(d)
        val rr = rec.into(r)

        val authors = dsl.selectFrom(a)
          .where(a.BOOK_ID.eq(br.id))
          .fetchInto(a)
          .filter { it.name != null }
          .map { AuthorDto(it.name, it.role) }

        br.toDto(mr.toDto(), dr.toDto(authors), if (rr.userId != null) rr.toDto() else null)
      }

  private fun BookSearch.toCondition(): Condition {
    var c: Condition = DSL.trueCondition()

    if (libraryIds.isNotEmpty()) c = c.and(b.LIBRARY_ID.`in`(libraryIds))
    if (seriesIds.isNotEmpty()) c = c.and(b.SERIES_ID.`in`(seriesIds))
    searchTerm?.let { c = c.and(d.TITLE.containsIgnoreCase(it)) }
    if (mediaStatus.isNotEmpty()) c = c.and(m.STATUS.`in`(mediaStatus))

    return c
  }

  private fun BookRecord.toDto(media: MediaDto, metadata: BookMetadataDto, readProgress: ReadProgressDto?) =
    BookDto(
      id = id,
      seriesId = seriesId,
      libraryId = libraryId,
      name = name,
      url = URL(url).toURI().path,
      number = number,
      created = createdDate.toUTC(),
      lastModified = lastModifiedDate.toUTC(),
      fileLastModified = fileLastModified.toUTC(),
      sizeBytes = fileSize,
      media = media,
      metadata = metadata,
      readProgress = readProgress
    )

  private fun MediaRecord.toDto() =
    MediaDto(
      status = status,
      mediaType = mediaType ?: "",
      pagesCount = pageCount.toInt(),
      comment = comment ?: ""
    )

  private fun BookMetadataRecord.toDto(authors: List<AuthorDto>) =
    BookMetadataDto(
      title = title,
      titleLock = titleLock,
      summary = summary,
      summaryLock = summaryLock,
      number = number,
      numberLock = numberLock,
      numberSort = numberSort,
      numberSortLock = numberSortLock,
      readingDirection = readingDirection ?: "",
      readingDirectionLock = readingDirectionLock,
      publisher = publisher,
      publisherLock = publisherLock,
      ageRating = ageRating,
      ageRatingLock = ageRatingLock,
      releaseDate = releaseDate,
      releaseDateLock = releaseDateLock,
      authors = authors,
      authorsLock = authorsLock
    )

  private fun ReadProgressRecord.toDto() =
    ReadProgressDto(
      page = page,
      completed = completed,
      created = createdDate.toUTC(),
      lastModified = lastModifiedDate.toUTC()
    )
}
