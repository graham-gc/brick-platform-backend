package apiworkflow.service;

import apiworkflow.controller.BrickController;
import apiworkflow.dto.SwaggerSyncImpact;
import apiworkflow.dto.SwaggerSyncPreviewReport;
import apiworkflow.dto.SwaggerSyncPreviewReport.ContractVersion;
import apiworkflow.dto.SwaggerSyncPreviewReport.Reason;
import apiworkflow.entity.AppSwaggerMapping;
import apiworkflow.entity.EndpointDefinition;
import apiworkflow.mapper.EndpointDefinitionMapper;
import apiworkflow.mapper.EndpointSchemaMapper;
import apiworkflow.mapper.SwaggerSyncImpactMapper;
import apiworkflow.swagger.EndpointSchemaResolver;
import apiworkflow.swagger.ParsedSwaggerDocument;
import apiworkflow.swagger.SwaggerDocumentParser;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SwaggerSyncImpactServiceTest {
    private static final String VARIABLE_FIELDS = "{\"name\":\"decision\",\"responsePath\":\"$.data.decision\"},"
            + "{\"name\":\"reasons\",\"responsePath\":\"$.data.records[*].reason\"},"
            + "{\"name\":\"retained\",\"responsePath\":\"$.data.records[0].retained\"}";
    private static final String VARIABLES = "[" + VARIABLE_FIELDS + "]";
    private static final String SCHEMA = "{\"properties\":{\"data\":{\"properties\":{"
            + "\"decision\":{\"type\":\"string\"},\"records\":{\"items\":{\"properties\":{"
            + "\"reason\":{\"type\":\"string\"},\"retained\":{\"type\":\"string\"}}}}}}}}";
    private static final String UPDATED_SCHEMA = SCHEMA.replace("\"decision\"", "\"reviewDecision\"")
            .replace("\"reason\"", "\"reviewReason\"");

    private final SwaggerDocumentParser parser = new SwaggerDocumentParser();
    private EmbeddedDatabase database;
    private SqlSessionFactory sessions;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        jdbc.execute("CREATE TABLE brick_endpoint_definition (id INT, swagger_mapping_id INT, is_deleted INT)");
        jdbc.execute("CREATE TABLE brick_flow (id INT, name VARCHAR(100), is_deleted INT)");
        jdbc.execute("CREATE TABLE brick_flow_node (id BIGINT, flow_id INT, endpoint_id INT, "
                + "response_variables_json VARCHAR(2000), node_type VARCHAR(20), is_deleted INT)");
        jdbc.execute("CREATE TABLE brick_flow_node_assertion (id BIGINT, node_id BIGINT, field_path VARCHAR(100), "
                + "assertion_type VARCHAR(20), is_enabled INT)");
        jdbc.update("INSERT INTO brick_endpoint_definition VALUES (25, 7, 0), (26, 8, 0), (27, 7, 1)");
        jdbc.update("INSERT INTO brick_flow VALUES (1, 'Valuation review', 0), (2, 'Deleted flow', 1)");
        jdbc.update("INSERT INTO brick_flow_node VALUES (101, 1, 25, ?, 'http', 0), "
                + "(102, 1, 25, ?, 'http', 1), (103, 2, 25, ?, 'http', 0), (104, 1, 26, ?, 'http', 0), "
                + "(105, 1, 27, ?, 'http', 0), (106, 1, 25, ?, 'grpc', 0)",
                VARIABLES, VARIABLES, VARIABLES, VARIABLES, VARIABLES, VARIABLES);
        jdbc.update("INSERT INTO brick_flow_node_assertion VALUES "
                + "(201, 101, '$.data.decision', 'json_path', 1), "
                + "(202, 101, '$.data.records[0].reason', 'json_path', 1), "
                + "(203, 101, '$.data.decision', 'json_path', 0), "
                + "(204, 101, '$.data.decision', 'header', 1)");
        Configuration configuration = new Configuration(new Environment("preview",
                new JdbcTransactionFactory(), database));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(SwaggerSyncImpactMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void reportsRemovedFieldsAndUnassessedReferencesSeparately() {
        new JdbcTemplate(database).update("UPDATE brick_flow_node SET response_variables_json = ? WHERE id = 101",
                "[" + VARIABLE_FIELDS + ",{\"name\":\"preexisting\",\"responsePath\":\"$.data.undeclared\"},"
                        + "{\"name\":\"complex\",\"responsePath\":\"$..decision\"}]");
        SwaggerSyncPreviewReport report = preview(contract("200", "application/json", UPDATED_SCHEMA));
        List<SwaggerSyncImpact> impacts = report.getImpacts();

        assertEquals(5, report.getCheckedCount());
        assertEquals(4, impacts.size());
        assertTrue(impacts.stream().allMatch(impact -> impact.getFlowId() == 1
                && impact.getNodeId() == 101L && "Valuation review".equals(impact.getFlowName())
                && "POST".equals(impact.getHttpMethod()) && "/assessment".equals(impact.getEndpointPath())
                && "200".equals(impact.getStatusCode())));
        Set<String> references = impacts.stream().map(impact -> impact.getReferenceType() + ":"
                + (impact.getAssertionId() == null ? impact.getVariableName() : impact.getAssertionId())
                + ":" + impact.getFieldPath()).collect(Collectors.toSet());
        assertEquals(new HashSet<>(Arrays.asList(
                "RESPONSE_VARIABLE:decision:$.data.decision",
                "RESPONSE_VARIABLE:reasons:$.data.records[*].reason",
                "ASSERTION:201:$.data.decision",
                "ASSERTION:202:$.data.records[0].reason")), references);
        assertEquals(2, report.getUnassessed().size());
        assertEquals(Reason.PATH_NOT_DECLARED, report.getUnassessed().get(0).getReason());
        assertEquals(ContractVersion.CURRENT, report.getUnassessed().get(0).getContractVersion());
        assertEquals(Reason.UNSUPPORTED_JSONPATH, report.getUnassessed().get(1).getReason());
        assertEquals("$..decision", report.getUnassessed().get(1).getReference().getFieldPath());
    }

    @Test
    void distinguishesCheckedAndUnchangedFromNoSavedReferences() {
        String unchanged = contract("200", "application/json", SCHEMA);
        SwaggerSyncPreviewReport report = preview(unchanged);
        assertEquals(5, report.getCheckedCount());
        assertTrue(report.getImpacts().isEmpty());
        assertTrue(report.getUnassessed().isEmpty());

        new JdbcTemplate(database).update("UPDATE brick_flow_node SET is_deleted = 1 WHERE id = 101");
        report = preview(unchanged);
        assertEquals(0, report.getCheckedCount());
        assertTrue(report.getImpacts().isEmpty());
        assertTrue(report.getUnassessed().isEmpty());
    }

    @Test
    void explainsMissingResponsesAndUnsupportedIncomingSchemas() {
        assertUnassessed(preview(contract("202", "application/json", UPDATED_SCHEMA)),
                Reason.RESPONSE_MISSING, ContractVersion.INCOMING);
        assertUnassessed(preview(contract("200", "application/problem+json", UPDATED_SCHEMA)),
                Reason.RESPONSE_MISSING, ContractVersion.INCOMING);
        assertUnassessed(preview(contract("200", "application/json",
                "{\"$ref\":\"#/components/schemas/Missing\"}")),
                Reason.UNRESOLVED_SCHEMA, ContractVersion.INCOMING);
        assertUnassessed(preview(contract("200", "application/json",
                "{\"anyOf\":[" + UPDATED_SCHEMA + "]}")),
                Reason.COMPOSED_SCHEMA, ContractVersion.INCOMING);
        assertUnassessed(preview(contract("200", "application/json", UPDATED_SCHEMA)
                .replace("{\"schema\":{\"$ref\":\"#/components/schemas/Assessment\"}}", "{}")),
                Reason.SCHEMA_MISSING, ContractVersion.INCOMING);
    }

    @Test
    void reportsComposedCurrentSchemaWithoutInferringFieldRemoval() {
        String stored = contract("200", "application/json", "{\"allOf\":[" + SCHEMA + "]}");
        assertUnassessed(preview(stored, contract("200", "application/json", UPDATED_SCHEMA)),
                Reason.COMPOSED_SCHEMA, ContractVersion.CURRENT);
    }

    @Test
    void canCompareOneResponseWhileAnotherResponseForTheSameReferenceIsUnassessed() {
        JSONObject stored = JSON.parseObject(contract("200", "application/json", SCHEMA));
        JSONObject responses = stored.getJSONObject("paths").getJSONObject("/assessment")
                .getJSONObject("post").getJSONObject("responses");
        responses.put("202", JSON.parseObject(responses.getJSONObject("200").toJSONString()));

        SwaggerSyncPreviewReport report = preview(stored.toJSONString(),
                contract("200", "application/json", UPDATED_SCHEMA));

        assertEquals(5, report.getCheckedCount());
        assertEquals(4, report.getImpacts().size());
        assertEquals(5, report.getUnassessed().size());
        assertTrue(report.getUnassessed().stream().allMatch(item -> item.getReason() == Reason.RESPONSE_MISSING
                && "202".equals(item.getReference().getStatusCode())));
    }

    @Test
    void servesLegacyArrayAndDetailedReportForTheSameRequest() throws Exception {
        String incoming = contract("200", "application/json", "{\"allOf\":[" + UPDATED_SCHEMA + "]}");
        JSONObject request = new JSONObject();
        request.put("swaggerMappingId", 7);
        request.put("swaggerContent", incoming);
        try (SqlSession session = sessions.openSession()) {
            IBrickApiService apiService = mock(IBrickApiService.class);
            AppSwaggerMapping mapping = new AppSwaggerMapping();
            mapping.setId(7);
            when(apiService.getById(7)).thenReturn(mapping);
            BrickController controller = new BrickController();
            ReflectionTestUtils.setField(controller, "apiService", apiService);
            ReflectionTestUtils.setField(controller, "swaggerSyncImpactService",
                    service(contract("200", "application/json", SCHEMA), session));
            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

            mvc.perform(post("/brick/sync/preview").contentType(MediaType.APPLICATION_JSON)
                            .content(request.toJSONString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
            mvc.perform(post("/brick/sync/preview/report").contentType(MediaType.APPLICATION_JSON)
                            .content(request.toJSONString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.checkedCount").value(0))
                    .andExpect(jsonPath("$.data.impacts").isEmpty())
                    .andExpect(jsonPath("$.data.unassessed[0].reason").value("COMPOSED_SCHEMA"))
                    .andExpect(jsonPath("$.data.unassessed[0].reference.nodeId").value(101));
        }
    }

    private void assertUnassessed(SwaggerSyncPreviewReport report, Reason reason, ContractVersion version) {
        assertEquals(0, report.getCheckedCount());
        assertTrue(report.getImpacts().isEmpty());
        assertEquals(5, report.getUnassessed().size());
        assertTrue(report.getUnassessed().stream().allMatch(item -> item.getReason() == reason
                && item.getContractVersion() == version));
    }

    private SwaggerSyncPreviewReport preview(String incoming) {
        return preview(contract("200", "application/json", SCHEMA), incoming);
    }

    private SwaggerSyncPreviewReport preview(String stored, String incoming) {
        try (SqlSession session = sessions.openSession()) {
            return service(stored, session).preview(7, incoming, null, null);
        }
    }

    private SwaggerSyncImpactService service(String document, SqlSession session) {
        ParsedSwaggerDocument stored = parser.parse(document, null, null);
        List<EndpointDefinition> endpoints = stored.getEndpoints();
        endpoints.get(0).setId(25);
        EndpointDefinitionMapper endpointMapper = mock(EndpointDefinitionMapper.class);
        EndpointSchemaMapper schemaMapper = mock(EndpointSchemaMapper.class);
        when(endpointMapper.selectAllBySwaggerMappingId(7, 0)).thenReturn(endpoints);
        when(schemaMapper.selectBySwaggerMappingId(7)).thenReturn(stored.getSchemas());
        return new SwaggerSyncImpactService(parser, endpointMapper,
                schemaMapper, session.getMapper(SwaggerSyncImpactMapper.class), new EndpointSchemaResolver());
    }

    private String contract(String status, String mediaType, String schema) {
        return "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"Valuation demo\",\"version\":\"1\"},"
                + "\"servers\":[{\"url\":\"http://localhost:9091/api/v1\"}],"
                + "\"paths\":{\"/assessment\":{\"post\":{\"responses\":{\"" + status + "\":"
                + "{\"description\":\"Assessment\",\"content\":{\"" + mediaType + "\":"
                + "{\"schema\":{\"$ref\":\"#/components/schemas/Assessment\"}}}}}}}},"
                + "\"components\":{\"schemas\":{\"Assessment\":" + schema + "}}}";
    }
}
