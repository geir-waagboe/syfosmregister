package no.nav.syfo.api

import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.Route
import io.ktor.routing.delete
import io.ktor.routing.route
import io.ktor.util.KtorExperimentalAPI
import no.nav.syfo.db.DatabaseInterface
import no.nav.syfo.db.nullstillSykmeldinger

@KtorExperimentalAPI
fun Route.registerNullstillApi(database: DatabaseInterface, clusterName: String) {
    route("/internal") {

        delete("/nullstillSykmeldinger/{aktorId}") {
            if (clusterName == "dev-fss") {
                val aktorId = call.parameters["aktorId"] ?: throw RuntimeException("Aktorid mangler i requesten")

                log.info("Nullstiller sykmeldinger på aktor: {}", aktorId)
                database.nullstillSykmeldinger(aktorId)
                call.respondText("ok!")
            } else {
                call.respond(HttpStatusCode.Forbidden)
            }
        }
    }
}
