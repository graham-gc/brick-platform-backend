package apiworkflow.service;

import apiworkflow.dto.SwaggerSyncImpact;
import apiworkflow.entity.EndpointDefinition;
import apiworkflow.mapper.EndpointDefinitionMapper;
import apiworkflow.mapper.EndpointSchemaMapper;
import apiworkflow.mapper.SwaggerSyncImpactMapper;
import apiworkflow.swagger.EndpointSchemaResolver;
import apiworkflow.swagger.ParsedSwaggerDocument;
import apiworkflow.swagger.SwaggerDocumentParser;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SwaggerSyncImpactServiceTest {
    private static final String VARIABLES = "["
            + "{\"name\":\"decision\",\"responsePath\":\"$.data.decision\"},"
            + "{\"name\":\"reasons\",\"responsePath\":\"$.data.records[*].reason\"},"
            + "{\"name\":\"retained\",\"responsePath\":\"$.data.records[0].retained\"},"
            + "{\"name\":\"preexisting\",\"responsePath\":\"$.data.undeclared\"},"
            + "{\"name\":\"complex\",\"responsePath\":\"$..decision\"}]";
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
    void findsActiveReferencesToRemovedFieldsThroughSchemaRefsAndArrayItems() {
        List<SwaggerSyncImpact> impacts = preview(contract("200", "application/json", UPDATED_SCHEMA));

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
    }

    @Test
    void ignoresUnchangedFieldsAndResponsesThatCannotBeCompared() {
        assertTrue(preview(contract("200", "application/json", SCHEMA)).isEmpty());
        assertTrue(preview(contract("202", "application/json", UPDATED_SCHEMA)).isEmpty());
        assertTrue(preview(contract("200", "application/problem+json", UPDATED_SCHEMA)).isEmpty());
        assertTrue(preview(contract("200", "application/json",
                "{\"$ref\":\"#/components/schemas/Missing\"}")).isEmpty());
        assertTrue(preview(contract("200", "application/json",
                "{\"anyOf\":[" + UPDATED_SCHEMA + "]}")).isEmpty());
    }

    private List<SwaggerSyncImpact> preview(String incoming) {
        ParsedSwaggerDocument stored = parser.parse(contract("200", "application/json", SCHEMA), null, null);
        List<EndpointDefinition> endpoints = stored.getEndpoints();
        endpoints.get(0).setId(25);
        EndpointDefinitionMapper endpointMapper = mock(EndpointDefinitionMapper.class);
        EndpointSchemaMapper schemaMapper = mock(EndpointSchemaMapper.class);
        when(endpointMapper.selectAllBySwaggerMappingId(7, 0)).thenReturn(endpoints);
        when(schemaMapper.selectBySwaggerMappingId(7)).thenReturn(stored.getSchemas());
        try (SqlSession session = sessions.openSession()) {
            SwaggerSyncImpactService service = new SwaggerSyncImpactService(parser, endpointMapper,
                    schemaMapper, session.getMapper(SwaggerSyncImpactMapper.class), new EndpointSchemaResolver());
            return service.preview(7, incoming, null, null);
        }
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
