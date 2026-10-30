package com.axcore.platform.audit.domain;

/** 누가 했는지. 사람 없이 시스템이 하거나 끝낸 일은 SYSTEM이다. */
public enum ActorType {
    USER,
    /** Agent가 사용자를 대신함. actor는 그 사용자다. */
    AGENT,
    SYSTEM
}
