package com.blockchainsearcher.domain;

/** The ways an address can be involved in a transaction. */
public enum MatchType {
    /** The address signed/sent the transaction. */
    TX_SENDER,
    /** The address is the transaction's {@code to}. */
    TX_RECIPIENT,
    /** The transaction deployed a contract at the address. */
    CONTRACT_CREATED,
    /** BENJI {@code DividendDistributed} event for the address. */
    DIVIDEND_DISTRIBUTED,
    /** ERC-20 Transfer in or out of the address (mints and burns included). */
    TOKEN_TRANSFER,
    /** ERC-721 Transfer in or out of the address. */
    NFT_TRANSFER,
    /** ERC-1155 TransferSingle/TransferBatch involving the address. */
    MULTI_TOKEN_TRANSFER,
    /** Any other decoded event with the address in an address-typed parameter. */
    EVENT_PARAMETER,
    /** The address is the contract that emitted a log. */
    EVENT_EMITTER,
    /** Undecoded log with the address in an indexed topic. */
    LOG_TOPIC,
    /** Undecoded log with the address in a 32-byte data word. */
    LOG_DATA,
    /** The address appears in the transaction input (including nested calldata). */
    CALLDATA,
    /** The address is the sender or target of an internal call. */
    INTERNAL_CALL
}
