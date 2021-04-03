/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okio

/**
 * An open file for reading and writing; using either streaming and random access.
 *
 * Use [read] and [write] to perform one-off random-access reads and writes. Use [source], [sink],
 * and [appendingSink] for streaming reads and writes.
 *
 * File handles must be closed when they are no longer needed. It is an error to read, write, or
 * create streams after a file handle is closed. The operating system resources held by a file
 * handle will be released once the file handle **and** all of its streams are closed.
 *
 * Although this class offers both reading and writing APIs, file handle instances may be
 * read-only or write-only. For example, a handle to a file on a read-only file system will throw an
 * exception if a write is attempted.
 *
 * File handles may be used by multiple threads concurrently. But the individual sources and sinks
 * produced by a file handle are not safe for concurrent use.
 */
@ExperimentalFileSystem
abstract class FileHandle : Closeable {
  /**
   * True once the file handle is closed. Resources should be released with [closeInternal] once
   * this is true and [openStreamCount] is 0.
   */
  private var closed = false

  /**
   * Reference count of the number of open sources and sinks on this file handle. Resources should
   * be released with [closeInternal] once this is 0 and [closed] is true.
   */
  private var openStreamCount = 0

  /**
   * Removes at least 1, and up to [byteCount] bytes from this and appends them to [sink]. Returns
   * the number of bytes read, or -1 if this file is exhausted.
   */
  @Throws(IOException::class)
  abstract fun read(offset: Long = 0L, sink: Buffer, byteCount: Long): Long

  /**
   * Returns the total number of bytes in the file. This will change if the file size changes.
   */
  @Throws(IOException::class)
  abstract fun size(): Long

  /** Removes [byteCount] bytes from [source] and writes them to this at [offset]. */
  @Throws(IOException::class)
  abstract fun write(offset: Long = 0L, source: Buffer, byteCount: Long)

  /** Pushes all buffered bytes to their final destination. */
  @Throws(IOException::class)
  abstract fun flush()

  /**
   * Returns a source that reads from this starting at [offset]. The returned source must be closed
   * when it is no longer needed.
   */
  @Throws(IOException::class)
  fun source(offset: Long = 0L): Source {
    synchronized(this) {
      check(!closed) { "closed" }
      openStreamCount++
    }
    return FileHandleSource(this, offset)
  }

  /**
   * Returns the position of [source] in the file. The argument [source] must be either a source
   * produced by this file handle, or a [BufferedSource] that directly wraps such a source. If the
   * parameter is a [BufferedSource], it adjusts for buffered bytes.
   */
  @Throws(IOException::class)
  fun position(source: Source): Long {
    var source = source
    var bufferSize = 0L

    if (source is RealBufferedSource) {
      bufferSize = source.buffer.size
      source = source.source
    }

    require(source is FileHandleSource && source.fileHandle === this) {
      "source was not created by this FileHandle"
    }

    return source.position - bufferSize
  }

  /**
   * Returns a sink that writes to this starting at [offset]. The returned sink must be closed when
   * it is no longer needed.
   */
  @Throws(IOException::class)
  fun sink(offset: Long = 0L): Sink {
    synchronized(this) {
      check(!closed) { "closed" }
      openStreamCount++
    }
    return FileHandleSink(this, offset)
  }

  /**
   * Returns a sink that writes to this starting at the end. The returned sink must be closed when
   * it is no longer needed.
   */
  @Throws(IOException::class)
  fun appendingSink(): Sink {
    return sink(size())
  }

  /**
   * Returns the position of [sink] in the file. The argument [sink] must be either a sink produced
   * by this file handle, or a [BufferedSink] that directly wraps such a sink. If the parameter is a
   * [BufferedSink], it adjusts for buffered bytes.
   */
  @Throws(IOException::class)
  fun position(sink: Sink): Long {
    var sink = sink
    var bufferSize = 0L

    if (sink is RealBufferedSink) {
      bufferSize = sink.buffer.size
      sink = sink.sink
    }

    require(sink is FileHandleSink && sink.fileHandle === this) {
      "sink was not created by this FileHandle"
    }

    return sink.position + bufferSize
  }

  @Throws(IOException::class)
  override fun close() {
    synchronized(this) {
      if (closed) return@close
      closed = true
      if (openStreamCount != 0) return@close
    }
    closeInternal()
  }

  /**
   * Subclasses should implement this to release resources held by this file handle. It is invoked
   * once both the file handle is closed, and also all sources and sinks produced by it are also
   * closed.
   */
  @Throws(IOException::class)
  protected abstract fun closeInternal()

  private class FileHandleSink(
    val fileHandle: FileHandle,
    var position: Long
  ) : Sink {
    var closed = false

    override fun write(source: Buffer, byteCount: Long) {
      fileHandle.write(position, source, byteCount)
      position += byteCount
    }

    override fun flush() {
      fileHandle.flush()
    }

    override fun timeout() = Timeout.NONE

    override fun close() {
      synchronized(fileHandle) {
        if (closed) return@close
        closed = true
        fileHandle.openStreamCount--
        if (fileHandle.openStreamCount != 0 || !fileHandle.closed) return@close
      }
      fileHandle.closeInternal()
    }
  }

  private class FileHandleSource(
    val fileHandle: FileHandle,
    var position: Long
  ) : Source {
    var closed = false

    override fun read(sink: Buffer, byteCount: Long): Long {
      val result = fileHandle.read(position, sink, byteCount)
      if (result != -1L) position += result
      return result
    }

    override fun timeout() = Timeout.NONE

    override fun close() {
      synchronized(fileHandle) {
        if (closed) return@close
        closed = true
        fileHandle.openStreamCount--
        if (fileHandle.openStreamCount != 0 || !fileHandle.closed) return@close
      }
      fileHandle.closeInternal()
    }
  }
}
