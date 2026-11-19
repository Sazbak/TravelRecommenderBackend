package com.example

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ServerTest {

    @Test
    fun `unknown root endpoint returns not found`() = testApplication {
        application {
            rootModule { throw AssertionError("Unknown routes must not invoke recommendations") }
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/").status)
    }

}
