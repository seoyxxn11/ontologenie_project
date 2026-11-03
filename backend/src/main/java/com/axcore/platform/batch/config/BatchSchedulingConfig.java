package com.axcore.platform.batch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 주기 작업(@Scheduled)은 배치 컨테이너에서만 돈다. api는 요청을 작업 표에 넣기만 한다(2026-10-30 결정). */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
@EnableScheduling
public class BatchSchedulingConfig {}
