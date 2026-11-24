package com.occlet.issuance.contracts;

/** Card ID and token. Never a PAN. */
public record CardRef(String cardId, String token) {}
