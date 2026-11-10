package com.example.todo

import com.example.todo.http.{Endpoints, HttpServer}
import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

import java.nio.file.{Files, Path}

/** Writes the OpenAPI spec for every endpoint. The frontend generates its TypeScript types from
  * this file, so the API contract is defined once, in Scala.
  *
  * Run with `sbt openapi` (writes `../frontend/openapi.yaml`) or pass an output path.
  */
object GenerateOpenApi:
  def main(args: Array[String]): Unit =
    val output = Path.of(args.headOption.getOrElse("../frontend/openapi.yaml"))
    val yaml = OpenAPIDocsInterpreter()
      .toOpenAPI(Endpoints.all, HttpServer.Title, HttpServer.Version)
      .toYaml
    Files.writeString(output, yaml)
    println(s"Wrote OpenAPI spec to ${output.toAbsolutePath.normalize}")
