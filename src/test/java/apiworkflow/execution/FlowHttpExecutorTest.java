package apiworkflow.execution;

import apiworkflow.entity.BrickFlow;
import apiworkflow.entity.BrickFlowNode;
import apiworkflow.entity.BrickFlowRunNode;
import apiworkflow.entity.EndpointDefinition;
import apiworkflow.mock.MockCommerceServer;
import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowHttpExecutorTest {

    private MockCommerceServer mockServer;
    private FlowHttpExecutor executor;
    private long nextNodeId;

    @BeforeEach
    void setUp() {
        mockServer = new MockCommerceServer(0);
        mockServer.start();
        executor = new FlowHttpExecutor();
        nextNodeId = 1L;
    }

    @AfterEach
    void tearDown() {
        mockServer.stop();
    }

    @Test
    void executesCompleteCommerceFlowAgainstRealHttpServer() {
        BrickFlow flow = new BrickFlow();

        BrickFlowRunNode login = execute(flow, "POST", "/auth/login",
                null, null, null,
                "{\"username\":\"demo.user\",\"password\":\"demo-password\"}");
        assertEquals("success", login.getStatus());
        assertEquals(Integer.valueOf(200), login.getHttpStatus());

        BrickFlowRunNode products = execute(flow, "GET", "/products",
                "{\"Authorization\":\"Bearer mock-access-token\"}",
                "{\"category\":\"models\",\"page\":1,\"pageSize\":20}", null, null);
        assertEquals("success", products.getStatus());

        BrickFlowRunNode createOrder = execute(flow, "POST", "/orders",
                "{\"Authorization\":\"Bearer mock-access-token\","
                        + "\"Content-Type\":\"application/json\",\"X-Request-Id\":\"req-flow-001\"}",
                null, null,
                "{\"customerId\":\"usr-1001\",\"items\":[{\"productId\":\"prd-1001\","
                        + "\"quantity\":2}],\"deliveryAddress\":{\"line1\":\"5 Example Street\","
                        + "\"city\":\"Auckland\",\"postcode\":\"1010\",\"country\":\"NZ\"}}");
        assertEquals("success", createOrder.getStatus());
        assertEquals(Integer.valueOf(201), createOrder.getHttpStatus());

        BrickFlowRunNode payment = execute(flow, "POST", "/orders/{orderId}/payments",
                "{\"Authorization\":\"Bearer mock-access-token\","
                        + "\"Content-Type\":\"application/json\",\"Idempotency-Key\":\"idem-flow-001\"}",
                null, "{\"orderId\":\"ord-2001\"}",
                "{\"paymentMethod\":\"TEST_CARD\",\"amount\":99.8,\"currency\":\"NZD\"}");
        assertEquals("success", payment.getStatus());

        BrickFlowRunNode getOrder = execute(flow, "GET", "/orders/{orderId}",
                "{\"Authorization\":\"Bearer mock-access-token\"}",
                null, "{\"orderId\":\"ord-2001\"}", null);
        assertEquals("success", getOrder.getStatus());
        assertEquals("PAID", JSON.parseObject(getOrder.getFullResponse())
                .getJSONObject("data").getString("status"));
    }

    private BrickFlowRunNode execute(BrickFlow flow, String method, String path,
                                     String headers, String query, String pathVariables, String body) {
        BrickFlowNode node = new BrickFlowNode();
        node.setId(nextNodeId++);
        node.setEndpointId((int) nextNodeId);
        node.setNodeType("http");
        node.setTimeoutSec(3);
        node.setHeadersJson(headers);
        node.setQueryParamsJson(query);
        node.setPathVarsJson(pathVariables);
        node.setPayloadJson(body);

        EndpointDefinition endpoint = new EndpointDefinition();
        endpoint.setId(node.getEndpointId());
        endpoint.setHttpMethod(method);
        endpoint.setBasePath("/api/v1");
        endpoint.setEndpointPath(path);
        endpoint.setFullUrl("http://localhost:" + mockServer.getPort() + "/api/v1" + path);

        return executor.execute(100L, flow, node, endpoint, null, null);
    }
}
