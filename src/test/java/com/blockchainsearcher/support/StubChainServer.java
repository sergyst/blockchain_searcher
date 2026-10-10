package com.blockchainsearcher.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-process stand-in for a JSON-RPC node and a Blockscout instance, serving recorded data for
 * Sepolia tx 0xb74ce488…3f10. No network access and no extra test dependencies needed.
 * <ul>
 *   <li>{@code /rpc} – JSON-RPC; {@code debug_traceTransaction} is rejected like on most public nodes</li>
 *   <li>{@code /blockscout/api/v2/...} – explorer API</li>
 *   <li>{@code /broken-blockscout/...} – always HTTP 503</li>
 * </ul>
 */
public final class StubChainServer implements AutoCloseable {

    public static final String TX_HASH = "0xb74ce488dfce325579e0791b9444b3bfbe07b68a0bd038690a445a8974e43f10";
    private static final String FIXTURES = "/fixtures/sepolia-0xb74ce488/";
    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpServer server;

    public StubChainServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/rpc", exchange -> rpc(exchange, false));
        // a lagging node: knows the mined tx but answers null to eth_getTransactionReceipt
        server.createContext("/rpc-no-receipt", exchange -> rpc(exchange, true));
        server.createContext("/blockscout", this::blockscout);
        server.createContext("/broken-blockscout", exchange -> respond(exchange, 503, "{\"message\":\"down\"}"));
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void rpc(HttpExchange exchange, boolean withholdReceipt) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = METHOD.matcher(body);
        String method = m.find() ? m.group(1) : "";
        boolean knownTx = body.toLowerCase().contains(TX_HASH);
        String response = switch (method) {
            case "eth_getTransactionByHash" -> knownTx ? fixture("rpc-transaction.json") : nullResult();
            case "eth_getTransactionReceipt" -> knownTx && !withholdReceipt ? fixture("rpc-receipt.json") : nullResult();
            case "eth_getBlockByNumber" -> fixture("rpc-block.json");
            case "eth_call" -> body.contains("0x313ce567") ? result(word("12"))       // decimals() = 18
                    : body.contains("0x95d89b41") ? result(word("20") + word("4")  // symbol() = "FUND"
                    + "46554e44" + "0".repeat(56)) : nullResult();
            default -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"the method "
                    + method + " does not exist/is not available\"}}";
        };
        respond(exchange, 200, response);
    }

    private void blockscout(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/blockscout/api/v2/transactions/" + TX_HASH + "/logs")) {
            respond(exchange, 200, fixture("blockscout-logs.json"));
        } else if (path.equals("/blockscout/api/v2/transactions/" + TX_HASH + "/internal-transactions")) {
            respond(exchange, 200, fixture("blockscout-internal-transactions.json"));
        } else {
            respond(exchange, 404, "{\"message\":\"Not found\"}");
        }
    }

    private static String result(String hex) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x" + hex + "\"}";
    }

    private static String nullResult() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}";
    }

    private static String word(String hex) {
        return "0".repeat(64 - hex.length()) + hex;
    }

    private static String fixture(String name) {
        try (InputStream in = StubChainServer.class.getResourceAsStream(FIXTURES + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
