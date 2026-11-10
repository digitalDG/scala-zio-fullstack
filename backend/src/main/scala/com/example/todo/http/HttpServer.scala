package com.example.todo.http

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.server.interceptor.CustomiseInterceptors
import sttp.tapir.server.interceptor.exception.ExceptionHandler
import sttp.tapir.server.model.ValuedEndpointOutput
import sttp.tapir.server.ziohttp.{ZioHttpInterpreter, ZioHttpServerOptions}
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.ztapir.ZServerEndpoint
import zio.*
import zio.http.{Response, Routes}

object HttpServer:
  val Title   = "Todo API"
  val Version = "1.0.0"

  /** Makes requests that fail before reaching our code (malformed JSON, a missing token, an
    * out-of-range query parameter) and unexpected exceptions return the same `ErrorResponse` JSON
    * as every other error. Shared with the tests so they exercise identical behaviour.
    */
  val interceptors: CustomiseInterceptors[Task, ZioHttpServerOptions[Any]] =
    ZioHttpServerOptions
      .customiseInterceptors[Any]
      .defaultHandlers(message =>
        ValuedEndpointOutput(jsonBody[ErrorResponse], ErrorResponse(ErrorCode.BadRequest, message))
      )
      .exceptionHandler(ExceptionHandler.pure[Task] { _ =>
        Some(
          ValuedEndpointOutput(
            statusCode.and(jsonBody[ErrorResponse]),
            (StatusCode.InternalServerError, ErrorResponse.internal)
          )
        )
      })

  /** The API plus Swagger UI at `/docs`. */
  def routes(api: List[ZServerEndpoint[Any, Any]]): Routes[Any, Response] =
    val docs = SwaggerInterpreter().fromEndpoints[Task](Endpoints.all, Title, Version)
    ZioHttpInterpreter(interceptors.options).toHttp(api ++ docs)
