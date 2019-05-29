package no.nav.syfo.api

import com.auth0.jwt.interfaces.Payload
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.application.install
import io.ktor.auth.authentication
import io.ktor.auth.jwt.JWTPrincipal
import io.ktor.features.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
import io.ktor.routing.routing
import io.ktor.server.testing.TestApplicationEngine
import io.ktor.server.testing.handleRequest
import io.ktor.util.KtorExperimentalAPI
import io.mockk.every
import io.mockk.mockk
import no.nav.syfo.aksessering.SykmeldingService
import no.nav.syfo.aksessering.api.FullstendigSykmeldingDTO
import no.nav.syfo.aksessering.api.PeriodetypeDTO
import no.nav.syfo.aksessering.api.SkjermetSykmeldingDTO
import no.nav.syfo.aksessering.api.registerSykmeldingApi
import no.nav.syfo.model.Adresse
import no.nav.syfo.model.AktivitetIkkeMulig
import no.nav.syfo.model.Arbeidsgiver
import no.nav.syfo.model.AvsenderSystem
import no.nav.syfo.model.Behandler
import no.nav.syfo.model.Diagnose
import no.nav.syfo.model.HarArbeidsgiver
import no.nav.syfo.model.KontaktMedPasient
import no.nav.syfo.model.MedisinskVurdering
import no.nav.syfo.model.Periode
import no.nav.syfo.model.Status
import no.nav.syfo.model.Sykmelding
import no.nav.syfo.model.ValidationResult
import no.nav.syfo.nullstilling.opprettSykmelding
import no.nav.syfo.objectMapper
import no.nav.syfo.persistering.PersistedSykmelding
import no.nav.syfo.testutil.TestDB
import no.nav.syfo.testutil.dropData
import org.amshove.kluent.shouldEqual
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import java.time.LocalDate
import java.time.LocalDateTime

@KtorExperimentalAPI
object SykmeldingApiSpek : Spek({

    val database = TestDB()

    afterGroup {
        database.stop()
    }

    describe("SykmeldingApi") {
        with(TestApplicationEngine()) {
            start()

            application.install(ContentNegotiation) {
                jackson {
                    registerKotlinModule()
                    registerModule(JavaTimeModule())
                    configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                }
            }

            application.routing {
                registerSykmeldingApi(SykmeldingService(database))
            }

            beforeEachTest {
                database.connection.opprettSykmelding(sykmelding)
            }

            afterEachTest {
                database.connection.dropData()
            }

            val mockPayload = mockk<Payload>()

            it("skal returnere status no content hvis bruker ikke har sykmeldinger") {
                every { mockPayload.subject } returns "AnnetPasientFnr"

                with(handleRequest(HttpMethod.Get, "/api/v1/sykmeldinger") {
                    call.authentication.principal = JWTPrincipal(mockPayload)
                }) {
                    response.status() shouldEqual HttpStatusCode.NoContent
                }
            }

            it("skal hente sykmeldinger for bruker") {
                every { mockPayload.subject } returns "pasientFnr"

                with(handleRequest(HttpMethod.Get, "/api/v1/sykmeldinger") {
                    call.authentication.principal = JWTPrincipal(mockPayload)
                }) {
                    response.status() shouldEqual HttpStatusCode.OK
                    objectMapper.readValue<List<FullstendigSykmeldingDTO>>(response.content!!)[0]
                        .sykmeldingsperioder[0]
                        .type shouldEqual PeriodetypeDTO.AKTIVITET_IKKE_MULIG
                }
            }

            it("skal ikke sende med medisinsk vurdering hvis sykmeldingen er skjermet") {
                database.connection.opprettSykmelding(
                    sykmelding.copy(
                        id = "uuid2",
                        pasientFnr = "PasientFnr1",
                        sykmelding = sykmelding.sykmelding.copy(
                            id = "id2",
                            skjermesForPasient = true
                        ))
                )
                every { mockPayload.subject } returns "PasientFnr1"

                with(handleRequest(HttpMethod.Get, "/api/v1/sykmeldinger") {
                    call.authentication.principal = JWTPrincipal(mockPayload)
                }) {
                    response.status() shouldEqual HttpStatusCode.OK
                    objectMapper.readValue<List<SkjermetSykmeldingDTO>>(response.content!!)[0]
                        .sykmeldingsperioder[0]
                        .type shouldEqual PeriodetypeDTO.AKTIVITET_IKKE_MULIG
                }
            }
        }
    }
})

val sykmelding = PersistedSykmelding(
    id = "uuid",
    pasientFnr = "pasientFnr",
    pasientAktoerId = "pasientAktorId",
    legeFnr = "legeFnr",
    legeAktoerId = "legeAktorId",
    mottakId = "eid-1",
    legekontorOrgNr = "lege-orgnummer",
    legekontorHerId = "legekontorHerId",
    legekontorReshId = "legekontorReshId",
    epjSystemNavn = "epjSystemNavn",
    epjSystemVersjon = "epjSystemVersjon",
    mottattTidspunkt = LocalDateTime.now(),
    sykmelding = Sykmelding(
        andreTiltak = "andreTiltak",
        arbeidsgiver = Arbeidsgiver(
            harArbeidsgiver = HarArbeidsgiver.EN_ARBEIDSGIVER,
            navn = "Arbeidsgiver AS",
            yrkesbetegnelse = "aktiv",
            stillingsprosent = 100
        ),
        avsenderSystem = AvsenderSystem(
            navn = "avsenderSystem",
            versjon = "versjon-1.0"
        ),
        behandler = Behandler(
            fornavn = "Fornavn",
            mellomnavn = "Mellomnavn",
            aktoerId = "legeAktorId",
            etternavn = "Etternavn",
            adresse = Adresse(
                gate = null,
                postboks = null,
                postnummer = null,
                kommune = null,
                land = null
            ),
            fnr = "legeFnr",
            hpr = "hpr",
            her = "her",
            tlf = "tlf"
        ),
        behandletTidspunkt = LocalDateTime.now(),
        id = "id",
        kontaktMedPasient = KontaktMedPasient(
            kontaktDato = null,
            begrunnelseIkkeKontakt = null
        ),
        medisinskVurdering = MedisinskVurdering(
            hovedDiagnose = Diagnose("2.16.578.1.12.4.1.1.7170", "Z01"),
            biDiagnoser = emptyList(),
            svangerskap = false,
            yrkesskade = false,
            yrkesskadeDato = null,
            annenFraversArsak = null
        ),
        meldingTilArbeidsgiver = "",
        meldingTilNAV = null,
        msgId = "msgId",
        pasientAktoerId = "pasientAktoerId",
        perioder = listOf(
            Periode(
                fom = LocalDate.now(),
                tom = LocalDate.now(),
                aktivitetIkkeMulig = AktivitetIkkeMulig(
                    medisinskArsak = null,
                    arbeidsrelatertArsak = null
                ),
                avventendeInnspillTilArbeidsgiver = null,
                behandlingsdager = null,
                gradert = null,
                reisetilskudd = false
            )
        ),
        prognose = null,
        signaturDato = LocalDateTime.now(),
        skjermesForPasient = false,
        syketilfelleStartDato = LocalDate.now(),
        tiltakArbeidsplassen = "tiltakArbeidsplassen",
        tiltakNAV = "tiltakNAV",
        utdypendeOpplysninger = emptyMap()
    ),
    behandlingsUtfall = ValidationResult(Status.OK, emptyList()),
    tssid = "13455"
)
