package apiworkflow.mock;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty(name = "brick.mock-commerce.enabled", havingValue = "true", matchIfMissing = true)
public class MockCommerceServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(MockCommerceServer.class);
    private static final String API_PREFIX = "/api/v1";
    private static final String ACCESS_TOKEN = "mock-access-token";

    private final int configuredPort;
    private final AtomicReference<String> orderStatus = new AtomicReference<>("PENDING_PAYMENT");
    private HttpServer server;
    private ExecutorService executor;

    public MockCommerceServer(@Value("${brick.mock-commerce.port:9090}") int configuredPort) {
        this.configuredPort = configuredPort;
    }

    @PostConstruct
    public synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", configuredPort), 0);
            executor = Executors.newFixedThreadPool(4, daemonThreadFactory());
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
            LOGGER.info("Mock Commerce API started at http://localhost:{}{}", getPort(), API_PREFIX);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start Mock Commerce API on port " + configuredPort, e);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public synchronized int getPort() {
        return server == null ? configuredPort : server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("OPTIONS".equals(method)) {
                sendEmpty(exchange, 204);
                return;
            }
            if ("GET".equals(method) && "/openapi.json".equals(path)) {
                sendOpenApiDocument(exchange);
                return;
            }
            if ("GET".equals(method) && (API_PREFIX + "/health").equals(path)) {
                sendJson(exchange, 200, object(
                        "status", "UP", "service", "mock-commerce", "version", "1.0.0"));
                return;
            }
            if ("POST".equals(method) && (API_PREFIX + "/auth/login").equals(path)) {
                login(exchange);
                return;
            }
            if (!isAuthorised(exchange)) {
                sendError(exchange, 401, "UNAUTHORIZED", "A valid bearer token is required");
                return;
            }
            if ("GET".equals(method) && (API_PREFIX + "/products").equals(path)) {
                listProducts(exchange);
                return;
            }
            if ("GET".equals(method) && path.startsWith(API_PREFIX + "/products/")) {
                getProduct(exchange, lastSegment(path));
                return;
            }
            if ("POST".equals(method) && (API_PREFIX + "/orders").equals(path)) {
                createOrder(exchange);
                return;
            }
            if (path.startsWith(API_PREFIX + "/orders/")) {
                handleOrderRoute(exchange, method, path);
                return;
            }
            sendError(exchange, 404, "NOT_FOUND", "Mock endpoint not found");
        } catch (Exception e) {
            LOGGER.error("Mock Commerce request failed", e);
            sendError(exchange, 500, "MOCK_SERVER_ERROR", e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void login(HttpExchange exchange) throws IOException {
        JSONObject request = readJsonBody(exchange);
        if (!"demo.user".equals(request.getString("username"))
                || !"demo-password".equals(request.getString("password"))) {
            sendError(exchange, 401, "INVALID_CREDENTIALS", "Username or password is incorrect");
            return;
        }
        JSONObject data = object(
                "accessToken", ACCESS_TOKEN,
                "tokenType", "Bearer",
                "expiresIn", 3600,
                "userId", "usr-1001");
        sendJson(exchange, 200, success("Login successful", data, "trace-login-001"));
    }

    private void listProducts(HttpExchange exchange) throws IOException {
        JSONObject first = product("prd-1001", "Model Kit Starter Set", "models", 49.90, 12);
        JSONObject second = product("prd-1002", "Precision Craft Tool Set", "tools", 29.50, 8);
        JSONObject data = object(
                "page", 1,
                "pageSize", 20,
                "total", 2,
                "items", JSON.parseArray("[" + first.toJSONString() + "," + second.toJSONString() + "]"));
        sendJson(exchange, 200, success("Products retrieved", data, "trace-products-001"));
    }

    private void getProduct(HttpExchange exchange, String productId) throws IOException {
        if (!"prd-1001".equals(productId) && !"prd-1002".equals(productId)) {
            sendError(exchange, 404, "PRODUCT_NOT_FOUND", "Product does not exist");
            return;
        }
        JSONObject product = "prd-1001".equals(productId)
                ? product("prd-1001", "Model Kit Starter Set", "models", 49.90, 12)
                : product("prd-1002", "Precision Craft Tool Set", "tools", 29.50, 8);
        sendJson(exchange, 200, success("Product retrieved", product, "trace-product-001"));
    }

    private void createOrder(HttpExchange exchange) throws IOException {
        JSONObject request = readJsonBody(exchange);
        if (!request.containsKey("customerId") || !request.containsKey("items")) {
            sendError(exchange, 400, "INVALID_ORDER", "customerId and items are required");
            return;
        }
        orderStatus.set("PENDING_PAYMENT");
        exchange.getResponseHeaders().set("Location", API_PREFIX + "/orders/ord-2001");
        sendJson(exchange, 201, success("Order created", order(), "trace-order-001"));
    }

    private void handleOrderRoute(HttpExchange exchange, String method, String path) throws IOException {
        String remainder = path.substring((API_PREFIX + "/orders/").length());
        String[] segments = remainder.split("/");
        if (segments.length == 0 || !"ord-2001".equals(segments[0])) {
            sendError(exchange, 404, "ORDER_NOT_FOUND", "Order does not exist");
            return;
        }
        if (segments.length == 1 && "GET".equals(method)) {
            sendJson(exchange, 200, success("Order retrieved", order(), "trace-order-get-001"));
            return;
        }
        if (segments.length == 2 && "payments".equals(segments[1]) && "POST".equals(method)) {
            payOrder(exchange);
            return;
        }
        if (segments.length == 2 && "status".equals(segments[1]) && "PATCH".equals(method)) {
            updateOrderStatus(exchange);
            return;
        }
        sendError(exchange, 404, "NOT_FOUND", "Order operation does not exist");
    }

    private void payOrder(HttpExchange exchange) throws IOException {
        if (!hasHeader(exchange, "Idempotency-Key")) {
            sendError(exchange, 400, "MISSING_IDEMPOTENCY_KEY", "Idempotency-Key is required");
            return;
        }
        JSONObject request = readJsonBody(exchange);
        if (!"TEST_CARD".equals(request.getString("paymentMethod"))) {
            sendError(exchange, 400, "INVALID_PAYMENT", "paymentMethod must be TEST_CARD");
            return;
        }
        orderStatus.set("PAID");
        JSONObject payment = object(
                "paymentId", "pay-3001",
                "orderId", "ord-2001",
                "status", "PAID",
                "amount", 99.80,
                "currency", "NZD",
                "paidAt", "2026-08-14T10:01:00+12:00");
        sendJson(exchange, 200, success("Payment accepted", payment, "trace-payment-001"));
    }

    private void updateOrderStatus(HttpExchange exchange) throws IOException {
        JSONObject request = readJsonBody(exchange);
        String status = request.getString("status");
        if (status == null) {
            sendError(exchange, 400, "INVALID_STATUS", "status is required");
            return;
        }
        orderStatus.set(status);
        sendJson(exchange, 200, success("Order status updated", order(), "trace-status-001"));
    }

    private JSONObject order() {
        JSONObject item = object(
                "productId", "prd-1001",
                "productName", "Model Kit Starter Set",
                "quantity", 2,
                "unitPrice", 49.90);
        return object(
                "id", "ord-2001",
                "customerId", "usr-1001",
                "status", orderStatus.get(),
                "currency", "NZD",
                "totalAmount", 99.80,
                "items", JSON.parseArray("[" + item.toJSONString() + "]"),
                "createdAt", "2026-08-14T10:00:00+12:00");
    }

    private JSONObject product(String id, String name, String category, double price, int stock) {
        return object(
                "id", id,
                "name", name,
                "category", category,
                "price", price,
                "currency", "NZD",
                "stock", stock);
    }

    private JSONObject success(String message, Object data, String traceId) {
        return object("code", "SUCCESS", "message", message, "data", data, "traceId", traceId);
    }

    private void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        sendJson(exchange, status, object(
                "code", code,
                "message", message == null ? code : message,
                "traceId", "trace-error-001"));
    }

    private JSONObject readJsonBody(HttpExchange exchange) throws IOException {
        String body = readUtf8(exchange.getRequestBody());
        if (body.trim().isEmpty()) {
            return new JSONObject(true);
        }
        return JSON.parseObject(body);
    }

    private boolean isAuthorised(HttpExchange exchange) {
        return ("Bearer " + ACCESS_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private boolean hasHeader(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value != null && !value.trim().isEmpty();
    }

    private void sendOpenApiDocument(HttpExchange exchange) throws IOException {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("mock-commerce-openapi.json");
        if (stream == null) {
            sendError(exchange, 404, "OPENAPI_NOT_FOUND", "Mock OpenAPI document is unavailable");
            return;
        }
        byte[] body;
        try (InputStream input = stream) {
            body = readBytes(input);
        }
        send(exchange, 200, "application/json; charset=utf-8", body);
    }

    private void sendJson(HttpExchange exchange, int status, JSONObject body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                body.toJSONString().getBytes(StandardCharsets.UTF_8));
    }

    private void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private String readUtf8(InputStream stream) throws IOException {
        return new String(readBytes(stream), StandardCharsets.UTF_8);
    }

    private byte[] readBytes(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private JSONObject object(Object... values) {
        JSONObject result = new JSONObject(true);
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "mock-commerce-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
