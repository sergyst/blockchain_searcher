# blockchain-searcher

Spring Boot service that answers one question: **is this address involved in this EVM transaction, and how?**

Built and tested against the BENJI dividend run on Sepolia
[`0xb74ce488…3f10`](https://eth-sepolia.blockscout.com/tx/0xb74ce488dfce325579e0791b9444b3bfbe07b68a0bd038690a445a8974e43f10?tab=logs),
where it finds the `DividendDistributed` record for `0x6B017Cb1a9A13d05b6ac24e978E0e6E683B418fa`.

## Run

Java 17+, Maven 3.9+.

```bash
mvn test                 # unit + full HTTP integration tests (no network needed)
mvn spring-boot:run
```

```bash
curl -s "http://localhost:8080/api/v1/transactions/0xb74ce488dfce325579e0791b9444b3bfbe07b68a0bd038690a445a8974e43f10/involvement?address=0x6B017Cb1a9A13d05b6ac24e978E0e6E683B418fa&network=sepolia"
```

The default public RPC endpoints are rate-limited. For real use, set your own:

```bash
SEPOLIA_RPC_URL=https://sepolia.infura.io/v3/<key> mvn spring-boot:run
```

## API

`GET /api/v1/transactions/{hash}/involvement?address=0x…&network=sepolia`

`network` is optional; `blockchain.default-network` is used when it is omitted.

Trimmed response for the transaction above:

```json
{
  "network": "sepolia",
  "involved": true,
  "summary": { "CALLDATA": 1, "TOKEN_TRANSFER": 1, "DIVIDEND_DISTRIBUTED": 1 },
  "matches": [
    { "type": "CALLDATA", "description": "Address appears 1 time(s) in the transaction input (inside nested calldata)" },
    { "type": "TOKEN_TRANSFER", "logIndex": 279, "description": "Received 0.199579979550020262 FUND (mint)" },
    {
      "type": "DIVIDEND_DISTRIBUTED",
      "logIndex": 280,
      "contract": "0x645Ba1c8aB1cb65Cf541F97a4f0cf30f58A471E4",
      "description": "Dividend of 0.199579979550020262 FUND for 2026-10-05 at 0.010188% per day (~3.71862% simple annualized), reinvested as 0.199579979550020262 shares on a balance of 1958.971138103850236557",
      "details": {
        "dateUtc": "2026-10-05T21:00Z",
        "rateDecimal": "0.00010188",
        "dividendCashAmount": "0.199579979550020262",
        "dividendBasis": "1958.971138103850236557",
        "isNegativeYield": false,
        "token": { "address": "0xEb0a4C608feD1B82DCDC98b565008316893CD6a0", "symbol": "FUND", "transferLogIndex": 279 }
      }
    }
  ],
  "sources": { "rpc": "ok", "internalCalls": "blockscout", "blockscout": "ok" },
  "warnings": []
}
```

`GET /api/v1/networks` lists the configured networks.

### Web3j variant

`GET /api/v1/transactions/{hash}/involvement/web3j?address=0x…&network=sepolia[&contract=0x…]`

`Web3jInvolvementService.findInvolvementWeb3j(network, hash, address)` asks the same question through Web3j
(`ethGetTransactionByHash`, `ethGetTransactionReceipt`, `ethGetBlockByNumber`, `ethCall`) instead of the
RestClient JSON-RPC client. It feeds the same analyzer, so for logs and calldata both paths return identical
matches. It uses the node only: there is no Blockscout enrichment and no internal-call check.

The optional `contract` parameter keeps only matches from logs that contract emitted, e.g.
`&contract=0x645Ba1c8aB1cb65Cf541F97a4f0cf30f58A471E4` for the BENJI dividend contract. Every log is still
analyzed, so a dividend is still paired with its share mint and the token is resolved.

Web3j is pinned to 4.13.x because 4.14+ requires Java 21.

Errors are RFC 9457 `application/problem+json`:

| Status | When |
|---|---|
| 400 | Malformed hash, address or network name, or an unknown network |
| 404 | The node does not know the transaction |
| 502 | The JSON-RPC node failed (it is the source of truth, so there is no answer without it) |

## What is checked

| Match type | Evidence |
|---|---|
| `TX_SENDER`, `TX_RECIPIENT`, `CONTRACT_CREATED` | Transaction and receipt fields |
| `DIVIDEND_DISTRIBUTED` | BENJI `DividendDistributed` event, paired with its share mint (or burn, for negative yield) so the token symbol and decimals are known |
| `TOKEN_TRANSFER` / `NFT_TRANSFER` / `MULTI_TOKEN_TRANSFER` | ERC-20 / ERC-721 / ERC-1155 transfer events |
| `EVENT_PARAMETER` | Any other decoded event with the address in an address parameter (decoded locally, or by Blockscout for unknown events) |
| `EVENT_EMITTER` | The address emitted a log |
| `LOG_TOPIC`, `LOG_DATA` | Undecoded logs whose topics or 32-byte data words contain the address |
| `CALLDATA` | The address appears anywhere in the input, including nested calldata |
| `INTERNAL_CALL` | Internal calls, from `debug_traceTransaction` when `trace-enabled`, otherwise from Blockscout |

## Data sources

* **JSON-RPC is the source of truth.** Transaction, receipt, block timestamp, and token `symbol()`/`decimals()` come from the node. All events listed above are decoded locally from raw logs.
* **Blockscout is an optional enricher.** It names events this service has no ABI for and supplies internal calls when tracing is off. If it is down or not configured, the answer still comes back, with a note in `warnings` and `sources`.

## Configuration

Networks are defined in `src/main/resources/application.yml`:

```yaml
blockchain:
  default-network: sepolia
  extra-events:                      # decode more events locally → EVENT_PARAMETER
    - "Paid(address indexed payee, uint256 amount)"
  networks:
    sepolia:
      chain-id: 11155111
      rpc-url: ${SEPOLIA_RPC_URL:https://ethereum-sepolia-rpc.publicnode.com}
      blockscout-url: https://eth-sepolia.blockscout.com   # omit to disable enrichment
      trace-enabled: false                                 # true only if your node allows debug_traceTransaction
```

## Layout

```
abi/        Keccak-256, ABI event decoding, event registry (incl. DividendDistributed)
analysis/   InvolvementAnalyzer – pure matching logic, no I/O
domain/     TxContext, Match, MatchType
rpc/        JSON-RPC client (RestClient)
blockscout/ Blockscout API v2 client (RestClient)
service/    Orchestration, per-network clients, token metadata cache
api/        Controller and response DTOs
error/      Problem-details exceptions and handler
```

Tests replay recorded data for the Sepolia transaction from `src/test/resources/fixtures`, through an in-process stub node and explorer.
