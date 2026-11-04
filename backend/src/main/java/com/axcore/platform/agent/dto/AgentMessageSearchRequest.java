package com.axcore.platform.agent.dto;

import jakarta.validation.constraints.Min;

public record AgentMessageSearchRequest(@Min(0) Integer page, @Min(1) Integer size) {}
