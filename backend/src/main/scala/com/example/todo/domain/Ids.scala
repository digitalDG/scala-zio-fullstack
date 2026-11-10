package com.example.todo.domain

import sttp.tapir.{Codec, CodecFormat}

/** Database identifiers as opaque types.
  *
  * At runtime these are plain `Long`s with zero overhead, but the compiler treats `UserId` and
  * `TodoId` as distinct types, so passing a todo ID where a user ID is expected doesn't compile.
  */
opaque type UserId = Long

object UserId:
  def apply(value: Long): UserId = value

  extension (id: UserId) def value: Long = id

opaque type TodoId = Long

object TodoId:
  def apply(value: Long): TodoId = value

  extension (id: TodoId) def value: Long = id

  /** Lets Tapir read a `TodoId` straight from a URL path segment. */
  given Codec[String, TodoId, CodecFormat.TextPlain] = Codec.long
