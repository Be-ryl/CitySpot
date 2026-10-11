package com.hmdp.service.impl;

public final class RabbitPublishResult {

    public enum Status {
        CONFIRMED,
        DEFINITELY_FAILED,
        UNKNOWN
    }

    private final Status status;
    private final String detail;

    private RabbitPublishResult(Status status, String detail) {
        this.status = status;
        this.detail = detail;
    }

    static RabbitPublishResult confirmed() {
        return new RabbitPublishResult(Status.CONFIRMED, null);
    }

    static RabbitPublishResult definitelyFailed(String detail) {
        return new RabbitPublishResult(Status.DEFINITELY_FAILED, detail);
    }

    static RabbitPublishResult unknown(String detail) {
        return new RabbitPublishResult(Status.UNKNOWN, detail);
    }

    public Status getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }
}
