package com.etrandafir.mcpswag.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.{Bean, Configuration, Primary}

@Configuration
class JacksonConfig:

  @Bean
  @Primary
  @ConditionalOnMissingBean
  def objectMapper(): ObjectMapper =
    JsonMapper.builder()
      .addModule(DefaultScalaModule)
      .build()
