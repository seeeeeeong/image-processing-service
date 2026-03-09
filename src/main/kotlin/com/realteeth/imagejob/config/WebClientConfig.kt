package com.realteeth.imagejob.config

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.util.concurrent.TimeUnit

@Configuration
class WebClientConfig(private val props: AppProperties) {

    @Bean
    fun mockWorkerWebClient(): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (props.mockWorker.connectTimeoutSeconds * 1000).toInt())
            .doOnConnected { conn ->
                conn.addHandlerLast(
                    ReadTimeoutHandler(props.mockWorker.readTimeoutSeconds, TimeUnit.SECONDS)
                )
            }

        return WebClient.builder()
            .baseUrl(props.mockWorker.baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .defaultHeader("Content-Type", "application/json")
            .build()
    }
}
