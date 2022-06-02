package no.nav.syfo

import com.auth0.jwk.JwkProviderBuilder
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.apache.Apache
import io.ktor.client.engine.apache.ApacheEngineConfig
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.network.sockets.SocketTimeoutException
import io.ktor.serialization.jackson.jackson
import io.prometheus.client.hotspot.DefaultExports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.logstash.logback.argument.StructuredArguments.fields
import no.nav.syfo.application.ApplicationServer
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.createApplicationEngine
import no.nav.syfo.application.exception.ServiceUnavailableException
import no.nav.syfo.application.getWellKnown
import no.nav.syfo.application.getWellKnownTokenX
import no.nav.syfo.application.proxyConfig
import no.nav.syfo.azuread.v2.AzureAdV2Client
import no.nav.syfo.db.Database
import no.nav.syfo.db.VaultCredentialService
import no.nav.syfo.identendring.IdentendringService
import no.nav.syfo.identendring.PdlAktorConsumer
import no.nav.syfo.kafka.aiven.KafkaUtils
import no.nav.syfo.kafka.toConsumerConfig
import no.nav.syfo.pdl.client.PdlClient
import no.nav.syfo.pdl.service.PdlPersonService
import no.nav.syfo.sykmelding.internal.tilgang.TilgangskontrollService
import no.nav.syfo.sykmelding.kafka.KafkaFactory.Companion.getBekreftetSykmeldingKafkaProducer
import no.nav.syfo.sykmelding.kafka.KafkaFactory.Companion.getKafkaConsumerPdlAktor
import no.nav.syfo.sykmelding.kafka.KafkaFactory.Companion.getKafkaStatusConsumerAiven
import no.nav.syfo.sykmelding.kafka.KafkaFactory.Companion.getMottattSykmeldingKafkaProducer
import no.nav.syfo.sykmelding.kafka.KafkaFactory.Companion.getSendtSykmeldingKafkaProducer
import no.nav.syfo.sykmelding.kafka.KafkaFactory.Companion.getSykmeldingStatusKafkaProducer
import no.nav.syfo.sykmelding.kafka.KafkaFactory.Companion.getTombstoneProducer
import no.nav.syfo.sykmelding.kafka.service.MottattSykmeldingStatusService
import no.nav.syfo.sykmelding.kafka.service.SykmeldingStatusConsumerService
import no.nav.syfo.sykmelding.service.BehandlingsutfallService
import no.nav.syfo.sykmelding.service.MottattSykmeldingService
import no.nav.syfo.sykmelding.service.SykmeldingerService
import no.nav.syfo.sykmelding.status.SykmeldingStatusService
import no.nav.syfo.util.util.Unbounded
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.time.ExperimentalTime

val objectMapper: ObjectMapper = ObjectMapper().apply {
    registerKotlinModule()
    registerModule(JavaTimeModule())
    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
}

val log: Logger = LoggerFactory.getLogger("nav.syfo.syfosmregister")

@DelicateCoroutinesApi
@ExperimentalTime
fun main() {
    val environment = Environment()
    val vaultServiceUser = VaultServiceUser()
    val wellKnown = getWellKnown(environment.loginserviceIdportenDiscoveryUrl)
    val jwkProvider = JwkProviderBuilder(URL(wellKnown.jwks_uri))
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    val jwkProviderAadV2 = JwkProviderBuilder(URL(environment.jwkKeysUrlV2))
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    val wellKnownTokenX = getWellKnownTokenX(environment.tokenXWellKnownUrl)
    val jwkProviderTokenX = JwkProviderBuilder(URL(wellKnownTokenX.jwks_uri))
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    val vaultCredentialService = VaultCredentialService()
    val database = Database(environment, vaultCredentialService)

    val applicationState = ApplicationState()

    DefaultExports.initialize()

    val kafkaBaseConfigAiven = KafkaUtils.getAivenKafkaConfig().also {
        it.let {
            it[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] = "1"
            it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "none"
        }
    }

    val sykmeldingStatusKafkaProducer = getSykmeldingStatusKafkaProducer(kafkaBaseConfigAiven, environment)
    val sykmeldingStatusService = SykmeldingStatusService(database)
    val sendtSykmeldingKafkaProducer = getSendtSykmeldingKafkaProducer(kafkaBaseConfigAiven, environment)
    val bekreftSykmeldingKafkaProducer = getBekreftetSykmeldingKafkaProducer(kafkaBaseConfigAiven, environment)
    val sykmeldingStatusKafkaConsumerAiven = getKafkaStatusConsumerAiven(kafkaBaseConfigAiven, environment)
    val tombstoneProducer = getTombstoneProducer(kafkaBaseConfigAiven, environment)
    val mottattSykmeldingKafkaProducer = getMottattSykmeldingKafkaProducer(kafkaBaseConfigAiven, environment)
    val mottattSykmeldingStatusService = MottattSykmeldingStatusService(sykmeldingStatusService, sendtSykmeldingKafkaProducer, bekreftSykmeldingKafkaProducer, tombstoneProducer, database, environment.cluster)
    val sykmeldingStatusConsumerService = SykmeldingStatusConsumerService(
        sykmeldingStatusKafkaConsumerAiven = sykmeldingStatusKafkaConsumerAiven,
        applicationState = applicationState,
        mottattSykmeldingStatusService = mottattSykmeldingStatusService
    )

    val receivedSykmeldingKafkaConsumerAiven = KafkaConsumer<String, String>(
        kafkaBaseConfigAiven
            .toConsumerConfig("${environment.applicationName}-consumer", valueDeserializer = StringDeserializer::class)
            .also { it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "none" }
    )
    val mottattSykmeldingService = MottattSykmeldingService(
        applicationState = applicationState,
        env = environment,
        kafkaAivenConsumer = receivedSykmeldingKafkaConsumerAiven,
        database = database,
        sykmeldingStatusKafkaProducer = sykmeldingStatusKafkaProducer,
        mottattSykmeldingKafkaProducer = mottattSykmeldingKafkaProducer,
        mottattSykmeldingStatusService = mottattSykmeldingStatusService
    )

    val behandlingsutfallKafkaConsumerAiven = KafkaConsumer<String, String>(
        kafkaBaseConfigAiven
            .toConsumerConfig("${environment.applicationName}-consumer", valueDeserializer = StringDeserializer::class)
            .also { it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "none" }
    )
    val behandlingsutfallService = BehandlingsutfallService(
        applicationState = applicationState,
        kafkaAivenConsumer = behandlingsutfallKafkaConsumerAiven,
        env = environment,
        database = database
    )

    val config: HttpClientConfig<ApacheEngineConfig>.() -> Unit = {
        install(ContentNegotiation) {
            jackson {
                registerKotlinModule()
                registerModule(JavaTimeModule())
                configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
        }
        HttpResponseValidator {
            handleResponseExceptionWithRequest { exception, _ ->
                when (exception) {
                    is SocketTimeoutException -> throw ServiceUnavailableException(exception.message)
                }
            }
        }
    }

    val httpClient = HttpClient(Apache, config)

    val httpProxyClient = HttpClient(Apache, proxyConfig)
    val azureAdV2Client = AzureAdV2Client(environment.clientIdV2, environment.clientSecretV2, environment.azureTokenEndpoint, httpProxyClient)
    val tilgangskontrollService = TilgangskontrollService(azureAdV2Client, httpClient, environment.syfoTilgangskontrollUrl, environment.syfotilgangskontrollClientId)

    val pdlClient = PdlClient(
        httpClient,
        environment.pdlGraphqlPath,
        PdlClient::class.java.getResource("/graphql/getPerson.graphql").readText().replace(Regex("[\n\t]"), "")
    )
    val pdlService = PdlPersonService(pdlClient, azureAdV2Client, environment.pdlScope)

    val identendringService = IdentendringService(database, sendtSykmeldingKafkaProducer, pdlService)
    val pdlAktorConsumer = PdlAktorConsumer(getKafkaConsumerPdlAktor(vaultServiceUser, environment), applicationState, environment.pdlAktorTopic, identendringService)

    val sykmeldingerService = SykmeldingerService(database)

    val applicationEngine = createApplicationEngine(
        env = environment,
        applicationState = applicationState,
        database = database,
        jwkProvider = jwkProvider,
        jwkProviderTokenX = jwkProviderTokenX,
        issuer = wellKnown.issuer,
        tokenXIssuer = wellKnownTokenX.issuer,
        sykmeldingStatusService = sykmeldingStatusService,
        jwkProviderAadV2 = jwkProviderAadV2,
        sykmeldingerService = sykmeldingerService,
        tilgangskontrollService = tilgangskontrollService
    )

    RenewVaultService(vaultCredentialService, applicationState).startRenewTasks()
    pdlAktorConsumer.startConsumer()
    launchListeners(
        applicationState = applicationState,
        sykmeldingStatusConsumerService = sykmeldingStatusConsumerService,
        mottattSykmeldingService = mottattSykmeldingService,
        behandlingsutfallService = behandlingsutfallService
    )

    ApplicationServer(applicationEngine, applicationState).start()
}

@DelicateCoroutinesApi
fun createListener(applicationState: ApplicationState, action: suspend CoroutineScope.() -> Unit): Job =
    GlobalScope.launch(Dispatchers.Unbounded) {
        try {
            action()
        } catch (e: TrackableException) {
            log.error("En uhåndtert feil oppstod, applikasjonen restarter {}", fields(e.loggingMeta), e.cause)
        } catch (ex: Exception) {
            log.error("Noe gikk galt", ex.cause)
        } finally {
            applicationState.alive = false
            applicationState.ready = false
        }
    }

@DelicateCoroutinesApi
fun launchListeners(
    applicationState: ApplicationState,
    sykmeldingStatusConsumerService: SykmeldingStatusConsumerService,
    mottattSykmeldingService: MottattSykmeldingService,
    behandlingsutfallService: BehandlingsutfallService
) {
    createListener(applicationState) {
        mottattSykmeldingService.start()
    }
    createListener(applicationState) {
        behandlingsutfallService.start()
    }
    createListener(applicationState) {
        sykmeldingStatusConsumerService.start()
    }
}
