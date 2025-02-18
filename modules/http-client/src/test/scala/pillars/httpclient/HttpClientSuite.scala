// Copyright (c) 2024-2024 by Raphaël Lemaitre and Contributors
// This software is licensed under the Eclipse Public License v2.0 (EPL-2.0).
// For more information see LICENSE or https://opensource.org/license/epl-2-0

package pillars.httpclient

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import org.http4s.client.Client
import sttp.tapir.*

class HttpClientSuite extends munit.CatsEffectSuite, munit.Http4sMUnitSyntax:
    val fixture = Client.partialFixture(client => Resource.pure(HttpClient(HttpClient.Config())(client))) {
        case GET -> Root / "success" / input => Ok("Success")
        case GET -> Root / "error"           => NotFound("Error")
    }

    fixture.test("HttpClient should successfully call a public endpoint"): client =>
        val e: PublicEndpoint[String, String, String, Unit] =
            endpoint.get.in("success" / sttp.tapir.path[String].name("input")).out(stringBody).errorOut(stringBody)
        val response                                        = client.call(e, uri"http://localhost".some)("input")
        assertIO(response, "Success".asRight)

    fixture.test("HttpClient should handle a failing endpoint"): client =>
        val e: PublicEndpoint[Unit, String, String, Unit] =
            endpoint.get.in("error").out(stringBody).errorOut(stringBody)
        val response                                      = client.call(e, uri"http://localhost".some)(())
        assertIO(response, "Error".asLeft)
end HttpClientSuite
