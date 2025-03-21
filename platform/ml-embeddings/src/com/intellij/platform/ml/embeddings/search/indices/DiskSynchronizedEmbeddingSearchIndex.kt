// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ml.embeddings.search.indices

import ai.grazie.emb.FloatTextEmbedding
import com.intellij.concurrency.ConcurrentCollectionFactory
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.platform.ml.embeddings.search.indices.EntitySourceType.DEFAULT
import com.intellij.platform.ml.embeddings.search.utils.ScoredText
import com.intellij.platform.ml.embeddings.search.utils.SuspendingReadWriteLock
import com.intellij.util.containers.CollectionFactory
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.file.Path

/**
 * Concurrent [EmbeddingSearchIndex] that synchronizes all index change operations with disk and
 * allows simultaneous read operations from multiple consumers.
 * Incremental operations do not rewrite the whole storage file with embeddings.
 * Instead, they change only the corresponding sections in the file.
 */
open class DiskSynchronizedEmbeddingSearchIndex(val root: Path, override var limit: Int? = null) : EmbeddingSearchIndex {
  private var indexToId: MutableMap<Int, EntityId> = CollectionFactory.createSmallMemoryFootprintMap()
  private var idToEntry: MutableMap<EntityId, IndexEntry> = CollectionFactory.createSmallMemoryFootprintMap()
  private val uncheckedIds: MutableSet<EntityId> = ConcurrentCollectionFactory.createConcurrentSet()
  var changed: Boolean = false

  private val lock = SuspendingReadWriteLock()

  private val fileManager = LocalEmbeddingIndexFileManager(root)

  override suspend fun setLimit(value: Int?) = lock.write {
    if (value != null) {
      // Shrink index if necessary:
      while (idToEntry.size > value) {
        delete(indexToId[idToEntry.size - 1]!!, all = true, shouldSaveIds = false)
      }
      saveIds()
    }
    limit = value
  }

  private data class IndexEntry(
    var index: Int,
    var count: Int,
    val embedding: FloatTextEmbedding
  )

  override suspend fun getSize() = lock.read { idToEntry.size }

  override suspend fun contains(id: EntityId): Boolean = lock.read {
    id in idToEntry
  }

  override suspend fun lookup(id: EntityId): FloatTextEmbedding? = lock.read { idToEntry[id]?.embedding }

  override suspend fun clear() = lock.write {
    indexToId.clear()
    idToEntry.clear()
    uncheckedIds.clear()
    changed = false
  }

  override suspend fun onIndexingStart() {
    lock.write {
      uncheckedIds.clear()
      val uncheckedKeys = idToEntry.filterKeys { it.sourceType == DEFAULT }.keys
      uncheckedIds.addAll(uncheckedKeys)
    }
  }

  override suspend fun onIndexingFinish() = lock.write {
    if (uncheckedIds.size > 0) changed = true
    logger.debug { "Deleted ${uncheckedIds.size} unchecked ids" }
    uncheckedIds.forEach {
      delete(it, all = true, shouldSaveIds = false)
    }
    uncheckedIds.clear()
  }

  override suspend fun addEntries(values: Iterable<Pair<EntityId, FloatTextEmbedding>>, shouldCount: Boolean) =
    lock.write {
      for ((id, embedding) in values) {
        ensureActive()
        uncheckedIds.remove(id)
        val entry = idToEntry.getOrPut(id) {
          changed = true
          if (limit != null && idToEntry.size >= limit!!) return@write
          val index = idToEntry.size
          indexToId[index] = id
          IndexEntry(index = index, count = 0, embedding = embedding)
        }
        if (shouldCount || entry.count == 0) {
          entry.count += 1
        }
      }
    }

  override suspend fun saveToDisk() = lock.read { save() }

  override suspend fun loadFromDisk() = lock.write {
    val (ids, embeddings) = fileManager.loadIndex() ?: return@write
    val idToIndex = ids.withIndex().associate { it.value to it.index }
    val idToEmbedding = (ids zip embeddings).toMap()
    indexToId = CollectionFactory.createSmallMemoryFootprintMap(ids.withIndex().associate { it.index to it.value })
    idToEntry = CollectionFactory.createSmallMemoryFootprintMap(
      ids.associateWith { IndexEntry(index = idToIndex[it]!!, count = 0, embedding = idToEmbedding[it]!!) }
    )
  }

  override suspend fun offload() = lock.write {
    indexToId = CollectionFactory.createSmallMemoryFootprintMap()
    idToEntry = CollectionFactory.createSmallMemoryFootprintMap()
  }

  override suspend fun findClosest(searchEmbedding: FloatTextEmbedding, topK: Int, similarityThreshold: Double?): List<ScoredText> = lock.read {
    return@read idToEntry.mapValues { it.value.embedding }.findClosest(searchEmbedding, topK, similarityThreshold)
  }

  override suspend fun streamFindClose(searchEmbedding: FloatTextEmbedding, similarityThreshold: Double?): Flow<ScoredText> {
    return flow {
      lock.read {
        this@DiskSynchronizedEmbeddingSearchIndex.idToEntry // manually use the receiver here to make sure the property is not captured by reference
          .asSequence()
          .map { it.key to it.value.embedding }
          .streamFindClose(searchEmbedding, similarityThreshold)
          .forEach { emit(it) }
      }
    }
  }

  override suspend fun estimateMemoryUsage() = fileManager.embeddingSizeInBytes.toLong() * getSize()

  override fun estimateLimitByMemory(memory: Long): Int {
    return (memory / fileManager.embeddingSizeInBytes).toInt()
  }

  override suspend fun checkCanAddEntry(): Boolean = lock.read {
    limit == null || idToEntry.size < limit!!
  }

  private suspend fun save() {
    val ids = idToEntry.toList().sortedBy { it.second.index }.map { it.first }
    val embeddings = ids.map { idToEntry[it]!!.embedding }
    fileManager.saveIndex(ids = ids, embeddings = embeddings)
  }

  suspend fun deleteEntry(id: EntityId, syncToDisk: Boolean) = lock.write {
    delete(id = id, shouldSaveIds = syncToDisk)
  }

  suspend fun addEntry(id: EntityId, embedding: FloatTextEmbedding) = lock.write {
    uncheckedIds.remove(id)
    add(id = id, embedding = embedding)
  }

  /* Optimization for consequent delete and add operations */
  suspend fun updateEntry(id: EntityId, newId: EntityId, embedding: FloatTextEmbedding) = lock.write {
    if (id !in idToEntry) return@write
    if (idToEntry[id]!!.count == 1 && newId !in idToEntry) {
      val index = idToEntry[id]!!.index
      fileManager.set(index, embedding)

      idToEntry.remove(id)
      idToEntry[newId] = IndexEntry(index = index, count = 1, embedding = embedding)
      indexToId[index] = newId

      saveIds()
    }
    else {
      // Do not apply optimization
      delete(id)
      add(id = newId, embedding = embedding)
    }
  }

  private suspend fun add(id: EntityId, embedding: FloatTextEmbedding, shouldCount: Boolean = false) {
    val entry = idToEntry.getOrPut(id) {
      changed = true
      if (limit != null && idToEntry.size >= limit!!) return@add
      val index = idToEntry.size
      fileManager.set(index, embedding)
      indexToId[index] = id
      IndexEntry(index = index, count = 0, embedding = embedding)
    }
    if (shouldCount || entry.count == 0) {
      entry.count += 1
      if (entry.count == 1) {
        saveIds()
      }
    }
  }

  private suspend fun delete(id: EntityId, all: Boolean = false, shouldSaveIds: Boolean = true) {
    val entry = idToEntry[id] ?: return
    entry.count -= 1
    if (!all && entry.count > 0) return

    val lastIndex = idToEntry.size - 1
    val index = entry.index

    val movedId = indexToId[lastIndex]!!

    fileManager.removeAtIndex(index)
    indexToId[index] = movedId
    indexToId.remove(lastIndex)

    idToEntry[movedId]!!.index = index
    idToEntry.remove(id)

    if (shouldSaveIds) saveIds()
  }

  private suspend fun saveIds() {
    fileManager.saveIds(idToEntry.toList().sortedBy { it.second.index }.map { it.first })
  }

  override suspend fun clearBySourceType(sourceType: EntitySourceType) {
    lock.write {
      val idsToRemove = idToEntry.filterKeys { it.sourceType == sourceType }.keys
      idsToRemove.forEach { delete(it, all = true, shouldSaveIds = false) }
    }
  }

  companion object {
    private val logger = Logger.getInstance(DiskSynchronizedEmbeddingSearchIndex::class.java)
  }
}