package pillars.httpclient

import cats.effect.Async
import cats.effect.Resource
import cats.effect.std.Console
import cats.syntax.all.*
import fs2.io.net.Network
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.http4s.Request
import org.http4s.Response
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.netty.client.NettyClientBuilder
import org.typelevel.otel4s.trace.Tracer
import pillars.Module
import pillars.Modules
import pillars.Pillars
import pillars.PillarsError
import pillars.PillarsError.*
import pillars.Run
import sttp.tapir.AnyEndpoint
import sttp.tapir.DecodeResult
import sttp.tapir.Endpoint
import sttp.tapir.PublicEndpoint
import sttp.tapir.ValidationError
import sttp.tapir.client.http4s.Http4sClientInterpreter
import sttp.tapir.client.http4s.Http4sClientOptions

class Loader extends pillars.Loader:
    override type M[F[_]] = HttpClient[F]

    override def key: Module.Key = HttpClient.Key

    override def load[F[_]: Async: Network: Tracer: Console](
        context: pillars.Loader.Context[F],
        modules: Modules[F]
    ): Resource[F, HttpClient[F]] =
        NettyClientBuilder[F].withHttp2.withNioTransport.resource.map(HttpClient.apply)
end Loader

final case class HttpClient[F[_]: Async](client: org.http4s.client.Client[F])
    extends pillars.Module[F]:
    export client.*

    private val interpreter = Http4sClientInterpreter[F](Http4sClientOptions.default)

    def call[SI, I, EO, O, R](
        endpoint: PublicEndpoint[I, EO, O, R],
        uri: Option[Uri],
        handler: FailureHandler[F, EO, O] = FailureHandler.default[F, EO, O]
    )(input: I): F[Either[EO, O]] =
        callRequest(endpoint, uri)(interpreter.toRequest(endpoint, uri)(input))
    end call

    def callSecure[SI, I, EO, O, R](
        endpoint: Endpoint[SI, I, EO, O, R],
        uri: Option[Uri],
        handler: FailureHandler[F, EO, O] = FailureHandler.default[F, EO, O]
    )(securityInput: SI, input: I): F[Either[EO, O]] =
        callRequest(endpoint, uri)(interpreter.toSecureRequest(endpoint, uri)(securityInput)(input))
    end callSecure

    private[this] def callRequest[I, EO, O](
        endpoint: AnyEndpoint,
        uri: Option[Uri],
        handler: FailureHandler[F, EO, O] = FailureHandler.default[F, EO, O]
    )(interpret: (Request[F], Response[F] => F[DecodeResult[Either[EO, O]]])) =
        val (request, parseResponse) = interpret
        client
            .run(request)
            .use(parseResponse)
            .flatMap:
                case DecodeResult.Value(v)         => v.pure[F]
                case failure: DecodeResult.Failure => handler.handle(endpoint, uri, failure)
    end callRequest

end HttpClient

object HttpClient:
    def apply[F[_]](using p: Pillars[F]): Client[F] = p.module[HttpClient[F]](Key).client
    case object Key extends Module.Key:
        override def name: String = "http-client"

    enum Error(endpoint: AnyEndpoint, uri: Option[Uri], val number: ErrorNumber, val message: Message)
        extends PillarsError:
        case DecodingError(endpoint: AnyEndpoint, uri: Option[Uri], raw: String, cause: Throwable) extends Error(
              endpoint,
              uri,
              ErrorNumber(1001),
              Message.assume(s"Cannot decode output $raw. Cause is $cause")
            )
        case Missing(endpoint: AnyEndpoint, uri: Option[Uri])
            extends Error(endpoint, uri, ErrorNumber(1002), Message("Missing"))
        case Multiple[R](endpoint: AnyEndpoint, uri: Option[Uri], vs: Seq[R])
            extends Error(endpoint, uri, ErrorNumber(1003), Message("Multiple response"))
        case InvalidInput(endpoint: AnyEndpoint, uri: Option[Uri], errors: List[ValidationError[_]])
            extends Error(endpoint, uri, ErrorNumber(1004), Message("Invalid input"))
        case Mismatch(endpoint: AnyEndpoint, uri: Option[Uri], expected: String, actual: String)
            extends Error(endpoint, uri, ErrorNumber(1005), Message("Type mismatch"))

        override def code: Code = Code("HTTP")

        override def details: Option[Message] =
            Message.option(s"""
              |uri: $uri
              |endpoint: $endpoint
              |""")
    end Error
end HttpClient

trait FailureHandler[F[_], EO, O]:
    def handle(endpoint: AnyEndpoint, uri: Option[Uri], failure: DecodeResult.Failure): F[Either[EO, O]]

object FailureHandler:
    def default[F[_]: Async, EO, O]: FailureHandler[F, EO, O] =
        (endpoint: AnyEndpoint, uri: Option[Uri], failure: DecodeResult.Failure) =>
            import HttpClient.Error.*
            failure match
            case DecodeResult.Error(raw, error)          => DecodingError(endpoint, uri, raw, error).raiseError[F, Either[EO, O]]
            case DecodeResult.Missing                    => Missing(endpoint, uri).raiseError[F, Either[EO, O]]
            case DecodeResult.Multiple(vs)               => Multiple(endpoint, uri, vs).raiseError[F, Either[EO, O]]
            case DecodeResult.Mismatch(expected, actual) =>
                Mismatch(endpoint, uri, expected, actual).raiseError[F, Either[EO, O]]
            case DecodeResult.InvalidValue(errors)       => InvalidInput(endpoint, uri, errors).raiseError[F, Either[EO, O]]
            end match
end FailureHandler

private[httpclient] final case class Config(followRedirect: Boolean)
extension [F[_]](p: Pillars[F])
    def httpClient: HttpClient[F] = p.module[HttpClient[F]](HttpClient.Key)

extension [I, EO, O, R](endpoint: PublicEndpoint[I, EO, O, R])
    def call[F[_]](uri: Option[Uri])(input: I): Run[F, F[Either[EO, O]]] =
        summon[Pillars[F]].httpClient.call(endpoint, uri)(input)

extension [SI, I, EO, O, R](endpoint: Endpoint[SI, I, EO, O, R])
    def call[F[_]](uri: Option[Uri])(securityInput: SI, input: I): Run[F, F[Either[EO, O]]] =
        summon[Pillars[F]].httpClient.callSecure(endpoint, uri)(securityInput, input)
