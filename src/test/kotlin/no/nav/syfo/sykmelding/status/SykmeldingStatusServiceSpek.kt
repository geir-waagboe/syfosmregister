package no.nav.syfo.sykmelding.status

import no.nav.syfo.model.RuleInfo
import no.nav.syfo.model.Status
import no.nav.syfo.model.ValidationResult
import no.nav.syfo.persistering.Behandlingsutfall
import no.nav.syfo.persistering.lagreMottattSykmelding
import no.nav.syfo.persistering.opprettBehandlingsutfall
import no.nav.syfo.sykmelding.db.hentSporsmalOgSvar
import no.nav.syfo.sykmelding.service.SykmeldingerService
import no.nav.syfo.testutil.TestDB
import no.nav.syfo.testutil.dropData
import no.nav.syfo.testutil.getNowTickMillisOffsetDateTime
import no.nav.syfo.testutil.testBehandlingsutfall
import no.nav.syfo.testutil.testSykmeldingsdokument
import no.nav.syfo.testutil.testSykmeldingsopplysninger
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEqualTo
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import java.time.ZoneOffset

class SykmeldingStatusServiceSpek : Spek({

    val database = TestDB()
    val sykmeldingerService = SykmeldingerService(database)
    val sykmeldingStatusService = SykmeldingStatusService(database)

    beforeEachTest {
        database.connection.dropData()
        database.lagreMottattSykmelding(testSykmeldingsopplysninger, testSykmeldingsdokument)
        database.registerStatus(
            SykmeldingStatusEvent(
                testSykmeldingsopplysninger.id,
                testSykmeldingsopplysninger.mottattTidspunkt.atOffset(ZoneOffset.UTC),
                StatusEvent.APEN
            )
        )
        database.connection.opprettBehandlingsutfall(testBehandlingsutfall)
    }

    afterEachTest {
        database.connection.dropData()
    }

    afterGroup {
        database.stop()
    }

    describe("Test registrerStatus") {
        it("Skal ikke kaste feil hvis man oppdaterer med eksisterende status på nytt") {
            val confirmedDateTime = getNowTickMillisOffsetDateTime()
            val status = SykmeldingStatusEvent("uuid", confirmedDateTime, StatusEvent.BEKREFTET)
            sykmeldingStatusService.registrerStatus(status)
            sykmeldingStatusService.registrerStatus(status)

            val savedSykmelding = sykmeldingerService.getUserSykmelding("pasientFnr", null, null)[0]
            savedSykmelding.sykmeldingStatus.timestamp shouldBeEqualTo confirmedDateTime
        }

        it("Skal ikke hente sykmeldinger med status SLETTET") {
            val confirmedDateTime = getNowTickMillisOffsetDateTime()
            val status = SykmeldingStatusEvent("uuid", confirmedDateTime, StatusEvent.APEN)
            val deletedStatus = SykmeldingStatusEvent("uuid", confirmedDateTime.plusHours(1), StatusEvent.SLETTET)
            sykmeldingStatusService.registrerStatus(status)
            database.registerStatus(deletedStatus)

            val sykmeldinger = sykmeldingerService.getUserSykmelding("pasientFnr", null, null)

            sykmeldinger shouldBeEqualTo emptyList()
        }

        it("Skal kun hente sykmeldinger der status ikke er SLETTET") {
            val copySykmeldingDokument = testSykmeldingsdokument.copy(id = "uuid2")
            val copySykmeldingopplysning = testSykmeldingsopplysninger.copy(
                id = "uuid2",
                pasientFnr = "pasientFnr"
            )
            database.lagreMottattSykmelding(copySykmeldingopplysning, copySykmeldingDokument)
            database.registerStatus(
                SykmeldingStatusEvent(
                    copySykmeldingopplysning.id,
                    copySykmeldingopplysning.mottattTidspunkt.atOffset(ZoneOffset.UTC),
                    StatusEvent.APEN
                )
            )
            database.connection.opprettBehandlingsutfall(testBehandlingsutfall.copy(id = "uuid2"))

            val confirmedDateTime = getNowTickMillisOffsetDateTime()
            val deletedStatus = SykmeldingStatusEvent("uuid", confirmedDateTime.plusHours(1), StatusEvent.SLETTET)
            database.registerStatus(deletedStatus)

            val sykmeldinger = sykmeldingerService.getUserSykmelding("pasientFnr", null, null)

            sykmeldinger.size shouldBeEqualTo 1
            sykmeldinger.first().id shouldBeEqualTo "uuid2"
        }

        it("Skal hente alle statuser") {
            database.registerStatus(
                SykmeldingStatusEvent(
                    "uuid",
                    getNowTickMillisOffsetDateTime().plusSeconds(10),
                    StatusEvent.SENDT
                )
            )
            val sykmeldingStatuser = sykmeldingStatusService.getSykmeldingStatus("uuid", null)
            sykmeldingStatuser.size shouldBeEqualTo 2
        }

        it("Skal hente siste status") {
            database.registerStatus(
                SykmeldingStatusEvent(
                    "uuid",
                    getNowTickMillisOffsetDateTime().plusSeconds(10),
                    StatusEvent.SENDT
                )
            )
            val sykmeldingstatuser = sykmeldingStatusService.getSykmeldingStatus("uuid", "LATEST")
            sykmeldingstatuser.size shouldBeEqualTo 1
            sykmeldingstatuser[0].event shouldBeEqualTo StatusEvent.SENDT
            sykmeldingstatuser[0].erAvvist shouldBeEqualTo false
            sykmeldingstatuser[0].erEgenmeldt shouldBeEqualTo false
        }

        it("Status skal vises som avvist hvis sykmelding er avvist") {
            val copySykmeldingDokument = testSykmeldingsdokument.copy(id = "uuid2")
            val copySykmeldingopplysning = testSykmeldingsopplysninger.copy(
                id = "uuid2"
            )
            database.lagreMottattSykmelding(copySykmeldingopplysning, copySykmeldingDokument)
            database.registerStatus(
                SykmeldingStatusEvent(
                    copySykmeldingopplysning.id,
                    copySykmeldingopplysning.mottattTidspunkt.atOffset(ZoneOffset.UTC),
                    StatusEvent.APEN
                )
            )
            database.connection.opprettBehandlingsutfall(
                Behandlingsutfall(
                    id = "uuid2",
                    behandlingsutfall = ValidationResult(
                        Status.INVALID,
                        listOf(RuleInfo("navn", "message", "message", Status.INVALID))
                    )
                )
            )

            val sykmeldingstatuser = sykmeldingStatusService.getSykmeldingStatus("uuid2", "LATEST")

            sykmeldingstatuser[0].erAvvist shouldBeEqualTo true
        }

        it("Status skal vises som egenmeldt hvis sykmelding er egenmelding") {
            val copySykmeldingDokument = testSykmeldingsdokument.copy(id = "uuid2")
            val copySykmeldingopplysning = testSykmeldingsopplysninger.copy(
                id = "uuid2", epjSystemNavn = "Egenmeldt"
            )
            database.lagreMottattSykmelding(copySykmeldingopplysning, copySykmeldingDokument)
            database.registerStatus(
                SykmeldingStatusEvent(
                    copySykmeldingopplysning.id,
                    copySykmeldingopplysning.mottattTidspunkt.atOffset(ZoneOffset.UTC),
                    StatusEvent.APEN
                )
            )
            database.connection.opprettBehandlingsutfall(testBehandlingsutfall.copy(id = "uuid2"))

            val sykmeldingstatuser = sykmeldingStatusService.getSykmeldingStatus("uuid2", "LATEST")

            sykmeldingstatuser[0].erEgenmeldt shouldBeEqualTo true
        }

        it("registrer bekreftet skal ikke lagre spørsmål og svar om den ikke er nyest") {
            val sporsmal = listOf(Sporsmal("tekst", ShortName.FORSIKRING, Svar("uuid", 1, Svartype.JA_NEI, "NEI")))
            sykmeldingStatusService.registrerBekreftet(
                SykmeldingBekreftEvent(
                    "uuid",
                    testSykmeldingsopplysninger.mottattTidspunkt.atOffset(ZoneOffset.UTC).minusSeconds(1),
                    sporsmal
                )
            )
            val savedSporsmals = database.connection.use {
                it.hentSporsmalOgSvar("uuid")
            }
            savedSporsmals.size shouldBeEqualTo 0
        }

        it("registrer bekreft skal lagre spørsmål") {
            val sporsmal = listOf(Sporsmal("tekst", ShortName.FORSIKRING, Svar("uuid", 1, Svartype.JA_NEI, "NEI")))
            sykmeldingStatusService.registrerBekreftet(
                SykmeldingBekreftEvent(
                    "uuid",
                    testSykmeldingsopplysninger.mottattTidspunkt.atOffset(ZoneOffset.UTC).plusSeconds(1),
                    sporsmal
                )
            )
            val savedSporsmals = database.connection.use {
                it.hentSporsmalOgSvar("uuid")
            }
            savedSporsmals shouldBeEqualTo sporsmal
        }

        it("registrer APEN etter BEKREFTET skal slette sporsmal og svar") {
            val sporsmal = listOf(Sporsmal("tekst", ShortName.FORSIKRING, Svar("uuid", 1, Svartype.JA_NEI, "NEI")))
            sykmeldingStatusService.registrerBekreftet(
                SykmeldingBekreftEvent(
                    "uuid",
                    testSykmeldingsopplysninger.mottattTidspunkt.atOffset(ZoneOffset.UTC).plusSeconds(1),
                    sporsmal
                )
            )
            sykmeldingStatusService.registrerStatus(
                SykmeldingStatusEvent(
                    "uuid",
                    testSykmeldingsopplysninger.mottattTidspunkt.atOffset(ZoneOffset.UTC).plusSeconds(2),
                    StatusEvent.APEN
                )
            )
            val savedSporsmal2 = database.connection.use {
                it.hentSporsmalOgSvar("uuid")
            }
            savedSporsmal2.size shouldBeEqualTo 0
        }

        it("Skal kunne hente SendtSykmeldingUtenDiagnose selv om behandlingsutfall mangler") {
            database.connection.dropData()
            database.lagreMottattSykmelding(testSykmeldingsopplysninger, testSykmeldingsdokument)
            database.registerStatus(
                SykmeldingStatusEvent(
                    testSykmeldingsopplysninger.id,
                    testSykmeldingsopplysninger.mottattTidspunkt.atOffset(ZoneOffset.UTC),
                    StatusEvent.APEN
                )
            )
            database.registrerSendt(
                SykmeldingSendEvent(
                    testSykmeldingsopplysninger.id,
                    testSykmeldingsopplysninger.mottattTidspunkt.plusMinutes(5).atOffset(ZoneOffset.UTC),
                    ArbeidsgiverStatus(testSykmeldingsopplysninger.id, "orgnummer", null, "Bedrift"),
                    Sporsmal(
                        "Arbeidssituasjon", ShortName.ARBEIDSSITUASJON,
                        Svar("uuid", 1, Svartype.ARBEIDSSITUASJON, "ARBEIDSTAKER")
                    )
                ),
                SykmeldingStatusEvent(
                    testSykmeldingsopplysninger.id,
                    testSykmeldingsopplysninger.mottattTidspunkt.plusMinutes(5).atOffset(ZoneOffset.UTC),
                    StatusEvent.SENDT
                )
            )
            val sendtSykmelding = sykmeldingStatusService.getEnkelSykmelding(testSykmeldingsopplysninger.id)
            sendtSykmelding shouldNotBeEqualTo null
        }
    }
})
