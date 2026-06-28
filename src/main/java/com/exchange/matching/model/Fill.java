package com.exchange.matching.model;

public record Fill(
    long buyOrderId,
    long sellOrderId,
    long price,
    long quantity,
    long timestampNanos)
{}