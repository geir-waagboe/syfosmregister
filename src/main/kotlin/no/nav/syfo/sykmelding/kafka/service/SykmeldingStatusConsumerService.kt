package no.nav.syfo.sykmelding.kafka.service

import kotlinx.coroutines.delay
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.sykmelding.kafka.consumer.SykmeldingStatusKafkaConsumer
import org.slf4j.LoggerFactory

class SykmeldingStatusConsumerService(
    private val sykmeldingStatusKafkaConsumer: SykmeldingStatusKafkaConsumer,
    private val sykmeldingStatusKafkaConsumerAiven: SykmeldingStatusKafkaConsumer,
    private val applicationState: ApplicationState,
    private val mottattSykmeldingStatusService: MottattSykmeldingStatusService
) {

    companion object {
        private val log = LoggerFactory.getLogger(SykmeldingStatusConsumerService::class.java)
        private const val delayStart = 10_000L
    }

    private suspend fun run() {
        sykmeldingStatusKafkaConsumer.subscribe()
        while (applicationState.ready) {
            val kafkaEvents = sykmeldingStatusKafkaConsumer.poll()
            kafkaEvents.forEach {
                mottattSykmeldingStatusService.handleStatusEvent(it)
            }
            if (kafkaEvents.isNotEmpty()) {
                sykmeldingStatusKafkaConsumer.commitSync()
            }
            delay(100)
        }
    }

    suspend fun start() {
        while (applicationState.alive) {
            try {
                run()
            } catch (ex: Exception) {
                log.error("Error reading status from on-prem topic, trying again in {} milliseconds, error {}", delayStart, ex.message)
                sykmeldingStatusKafkaConsumer.unsubscribe()
            }
            delay(delayStart)
        }
    }

    suspend fun startAivenConsumer() {
        while (applicationState.alive) {
            try {
                runAivenConsumer()
            } catch (ex: Exception) {
                log.error("Error reading status from aiven topic, trying again in {} milliseconds, error {}", delayStart, ex.message)
                sykmeldingStatusKafkaConsumerAiven.unsubscribe()
            }
            delay(delayStart)
        }
    }

    private suspend fun runAivenConsumer() {
        sykmeldingStatusKafkaConsumerAiven.subscribe()
        while (applicationState.ready) {
            val kafkaEvents = sykmeldingStatusKafkaConsumerAiven.pollAndIgnoreOnPremRecords()
            kafkaEvents.forEach {
                mottattSykmeldingStatusService.handleStatusEvent(it, source = "aiven")
            }
            if (kafkaEvents.isNotEmpty()) {
                sykmeldingStatusKafkaConsumerAiven.commitSync()
            }
            delay(100)
        }
    }
}
