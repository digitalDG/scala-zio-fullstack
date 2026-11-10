package com.example.todo.domain

import sttp.tapir.{Codec, CodecFormat, DecodeResult, Schema, Validator}
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

/** Mix-in for the companion of an enum whose values travel over the wire as fixed string labels.
  *
  * Extending it gives the enum, from one definition:
  *   - a JSON codec (`"high"` <-> `Priority.High`)
  *   - a Tapir schema that appears in OpenAPI as a named string enum, which becomes a TypeScript
  *     union type (`"low" | "medium" | "high"`)
  *   - a Tapir codec so the enum can be used directly as a query parameter
  *
  * The givens are members of the companion, so they're found automatically wherever the enum is
  * used, with no imports.
  */
trait LabeledEnum[E]:
  def values: Array[E]
  def label(value: E): String
  protected def typeName: String

  final def fromLabel(raw: String): Option[E] = values.find(label(_) == raw)

  private def labels: String = values.map(label).mkString(", ")

  private def validator: Validator[E] =
    Validator.enumeration(values.toList, (e: E) => Some(label(e)), Some(Schema.SName(typeName)))

  given JsonCodec[E] = JsonCodec(
    JsonEncoder.string.contramap(label),
    JsonDecoder.string.mapOrFail(raw => fromLabel(raw).toRight(s"expected one of: $labels"))
  )

  given Schema[E] = Schema.string[E].name(Schema.SName(typeName)).validate(validator)

  given Codec[String, E, CodecFormat.TextPlain] =
    Codec.string
      .mapDecode(raw =>
        fromLabel(raw) match
          case Some(value) => DecodeResult.Value(value)
          case None        => DecodeResult.InvalidValue(Nil)
      )(label)
      .schema(summon[Schema[E]])
