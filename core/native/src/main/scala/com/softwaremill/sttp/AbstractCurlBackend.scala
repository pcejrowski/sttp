package com.softwaremill.sttp

import java.io.{ByteArrayInputStream, IOException}

import com.softwaremill.sttp.curl.CurlApi._
import com.softwaremill.sttp.curl.CurlCode.CurlCode
import com.softwaremill.sttp.curl.CurlInfo._
import com.softwaremill.sttp.curl.CurlOption._
import com.softwaremill.sttp.curl._
import com.softwaremill.sttp.internal._

import scala.collection.immutable.Seq
import scala.io.Source
import scala.scalanative.native
import scala.scalanative.native.stdio.FILE
import scala.scalanative.native.stdlib._
import scala.scalanative.native.string._
import scala.scalanative.native.{CSize, Ptr, _}

abstract class AbstractCurlBackend[R[_], S](rm: MonadError[R], verbose: Boolean) extends SttpBackend[R, S] {

  override val responseMonad: MonadError[R] = rm

  override def close(): Unit = {}

  private var headers: CurlList = _
  private var multiPartHeaders: Seq[CurlList] = Seq()
  private var outputFile: Ptr[FILE] = _

  override def send[T](request: Request[T, S]): R[Response[T]] = native.Zone { implicit z =>
    val curl = CurlApi.init
    curl.option(Verbose, verbose)
    if (request.tags.nonEmpty) {
      responseMonad.error(new UnsupportedOperationException("Tags are not supported"))
    }
    val reqHeaders = request.headers
    if (reqHeaders.size > 0) {
      reqHeaders.find(_._1 == "Accept-Encoding").foreach(h => curl.option(AcceptEncoding, h._2))
      request.body match {
        case _: MultipartBody =>
          headers = transformHeaders(reqHeaders :+ "Content-Type" -> "multipart/form-data")
        case _ =>
          headers = transformHeaders(reqHeaders)
      }
      curl.option(HttpHeader, headers.ptr)
    }

    val spaces = responseSpace
    setResponseHandling(curl, spaces, request.response)

    curl.option(HeaderData, spaces.headersResp)
    curl.option(Url, request.uri.toString)
    curl.option(TimeoutMs, request.options.readTimeout.toMillis)
    setMethod(curl, request.method)

    setRequestBody(curl, request.body)

    responseMonad.flatMap(lift(curl.perform)) { _ =>
      curl.info(ResponseCode, spaces.httpCode)
      val responseBody = fromCString(!spaces.bodyResp._1)
      val responseHeaders = parseHeaders(fromCString(!spaces.headersResp._1))
      val httpCode = (!spaces.httpCode).toInt

      if (headers.ptr != null) headers.ptr.free()
      multiPartHeaders.foreach(_.ptr.free())
      free(!spaces.bodyResp._1)
      free(!spaces.headersResp._1)
      free(spaces.bodyResp.cast[Ptr[CSignedChar]])
      free(spaces.headersResp.cast[Ptr[CSignedChar]])
      free(spaces.httpCode.cast[Ptr[CSignedChar]])
      curl.cleanup()

      val body: R[Either[Array[CSignedChar], T]] = if (StatusCodes.isSuccess(httpCode)) {
        responseMonad.map(readResponseBody(responseBody, request.response, responseHeaders))(Right.apply)
      } else {
        responseMonad.map(toByteArray(responseBody))(Left.apply)
      }
      responseMonad.map(body) { b =>
        Response[T](
          rawErrorBody = b,
          code = httpCode,
          statusText = "200", //responseHeaders.head._1.split(" ").last,
          headers = responseHeaders.tail,
          history = Nil
        )
      }
    }
  }

  private def setMethod(handle: CurlHandle, method: Method)(implicit z: Zone): R[CurlCode] = {
    val m = method match {
      case Method.GET     => handle.option(HttpGet, true)
      case Method.HEAD    => handle.option(Head, true)
      case Method.POST    => handle.option(Post, true)
      case Method.PUT     => handle.option(Put, true)
      case Method.DELETE  => handle.option(Post, true)
      case Method.OPTIONS => handle.option(RtspRequest, true)
      case Method.PATCH   => handle.option(CustomRequest, "PATCH")
      case Method.CONNECT => handle.option(ConnectOnly, true)
      case Method.TRACE   => handle.option(CustomRequest, "TRACE")
    }
    lift(m)
  }

  private def setRequestBody(curl: CurlHandle, body: RequestBody[S])(implicit zone: Zone): R[CurlCode] =
    body match {
      case b: BasicRequestBody =>
        val str = basicBodyToString(b)
        lift(curl.option(PostFields, toCString(str)))
      case MultipartBody(parts) =>
        val mime = curl.mime
        parts.foreach {
          case Multipart(name, partBody, fileName, contentType, additionalHeaders) =>
            val part = mime.addPart()
            part.withName(name)
            val str = basicBodyToString(partBody)
            part.withData(str)
            if (fileName.isDefined) {
              part.withFileName(fileName.get)
            }
            if (contentType.isDefined) {
              part.withMimeType(contentType.get)
            }
            if (additionalHeaders.size > 0) {
              val curlList = transformHeaders(additionalHeaders)
              part.withHeaders(curlList.ptr)
              multiPartHeaders = multiPartHeaders :+ curlList
            }
        }
        lift(curl.option(Mimepost, mime))
      case StreamBody(_) =>
        responseMonad.error(new IllegalStateException("CurlBackend does not support stream request body"))
      case NoBody =>
        responseMonad.unit(CurlCode.Ok)
    }

  private def basicBodyToString(body: BasicRequestBody): String =
    body match {
      case StringBody(b, _, _)   => b
      case ByteArrayBody(b, _)   => new String(b)
      case ByteBufferBody(b, _)  => new String(b.array)
      case InputStreamBody(b, _) => Source.fromInputStream(b).mkString
      case FileBody(f, _)        => Source.fromFile(f.toFile).mkString
    }

  private def setResponseHandling[T](curl: CurlHandle, spaces: CurlSpaces, responseAs: ResponseAs[T, S])(
      implicit zone: Zone): R[CurlCode] = {
    responseAs match {
      case MappedResponseAs(raw, _) => setResponseHandling(curl, spaces, raw)
      case ResponseAsFile(output, overwrite) =>
        val mode = if (overwrite) "wb" else "wbx"
        outputFile = stdio.fopen(toCString(output.name), toCString(mode))
        if (outputFile != null) {
          curl.option(FollowLocation, true)
          responseMonad.flatMap(lift(curl.option(WriteFunction, AbstractCurlBackend.wdFileFunc))) { _ =>
            lift(curl.option(WriteData, outputFile))
          }
        } else {
          responseMonad.error(new IOException("Cannot overwrite the file"))
        }
      case _ =>
        responseMonad.flatMap(lift(curl.option(WriteFunction, AbstractCurlBackend.wdFunc))) { _ =>
          lift(curl.option(WriteData, spaces.bodyResp))
        }
    }
  }

  private def responseSpace: CurlSpaces = {
    val bodyResp = malloc(sizeof[CurlFetch]).cast[Ptr[CurlFetch]]
    !bodyResp._1 = calloc(4096, sizeof[CChar]).cast[CString]
    !bodyResp._2 = 0
    val headersResp = malloc(sizeof[CurlFetch]).cast[Ptr[CurlFetch]]
    !headersResp._1 = calloc(4096, sizeof[CChar]).cast[CString]
    !headersResp._2 = 0
    val httpCode = malloc(sizeof[Long]).cast[Ptr[Long]]
    new CurlSpaces(bodyResp, headersResp, httpCode)
  }

  private def parseHeaders(str: String): Seq[(String, String)] = {
    val array = str
      .split("\n")
      .filter(_.trim.length > 0)
      .map { line =>
        val split = line.split(":", 2)
        if (split.size == 2)
          split(0).trim -> split(1).trim
        else
          split(0).trim -> ""
      }
    Seq.from(array: _*)
  }

  private def readResponseBody[T](response: String,
                                  responseAs: ResponseAs[T, S],
                                  headers: Seq[(String, String)]): R[T] = {

    responseAs match {
      case MappedResponseAs(raw, g) => responseMonad.map(readResponseBody(response, raw, headers))(g)
      case IgnoreResponse           => responseMonad.unit((): Unit)
      case ResponseAsString(enc) =>
        val charset = headers.toMap
          .get(HeaderNames.ContentType)
          .flatMap(encodingFromContentType)
          .getOrElse(enc)
        if (charset.compareToIgnoreCase(Utf8) == 0) responseMonad.unit(response)
        else responseMonad.map(toByteArray(response))(r => new String(r, charset.toUpperCase))
      case ResponseAsByteArray       => toByteArray(response)
      case ResponseAsFile(output, _) => responseMonad.unit(output)
      case ResponseAsStream() =>
        responseMonad.error(new IllegalStateException("CurlBackend does not support streaming responses"))
    }
  }

  private def transformHeaders(reqHeaders: Iterable[(String, String)])(implicit z: Zone): CurlList = {
    reqHeaders
      .map {
        case (headerName, headerValue) =>
          s"$headerName: $headerValue"
      }
      .foldLeft(new CurlList(null)) {
        case (acc, h) =>
          new CurlList(acc.ptr.append(h))
      }
  }

  private def toByteArray(str: String): R[Array[Byte]] = responseMonad.unit(str.toCharArray.map(_.toByte))

  private def lift(code: CurlCode): R[CurlCode] = {
    code match {
      case CurlCode.Ok => responseMonad.unit(code)
      case _           => responseMonad.error(new RuntimeException(s"Command failed with status $code"))
    }
  }
}

object AbstractCurlBackend {
  def writeData(ptr: Ptr[Byte], size: CSize, nmemb: CSize, data: Ptr[CurlFetch]): CSize = {
    val index: CSize = !data._2
    val increment: CSize = size * nmemb
    !data._2 = !data._2 + increment
    !data._1 = realloc(!data._1, !data._2 + 1)
    memcpy(!data._1 + index, ptr, increment)
    !(!data._1).+(!data._2) = 0.toByte
    size * nmemb
  }

  def fileWriteData(ptr: Ptr[Byte], size: CSize, nmemb: CSize, stream: Ptr[Unit]): CSize = {
    stdio.fwrite(ptr, size, nmemb, stream.cast[Ptr[FILE]])
  }

  val wdFunc = CFunctionPtr.fromFunction4(writeData)
  val wdFileFunc = CFunctionPtr.fromFunction4(fileWriteData)
}
