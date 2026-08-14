package apiworkflow.service;

import apiworkflow.entity.BrickFlow;
import apiworkflow.entity.BrickFlowEdge;
import apiworkflow.entity.BrickFlowNode;
import apiworkflow.entity.BrickFlowRun;
import apiworkflow.entity.BrickFlowRunNode;
import apiworkflow.entity.BrickFlowRunNodeAssertion;
import apiworkflow.entity.AppSwaggerMapping;
import apiworkflow.dto.*;
import java.util.List;
import java.util.Map;

public interface IBrickFlowService {

    BrickFlow getFlow(Integer id);

    List<BrickFlow> listFlows(BrickFlow query, int offset, int limit,
                               Integer endpointId, Integer grpcEndpointId, String executionStatus);

    int countFlows(BrickFlow query, Integer endpointId, Integer grpcEndpointId, String executionStatus);

    int createFlow(BrickFlow flow, List<BrickFlowNode> nodes, List<BrickFlowEdge> edges, String operator);

    int createFullFlow(BrickFlow flow, List<BrickFlowFullNode> fullNodes, List<BrickFlowEdge> edges, String operator);

    int updateFlow(BrickFlow flow, List<BrickFlowNode> nodes, List<BrickFlowEdge> edges, String operator);

    int deleteFlow(Integer id, String operator);

    int hardDeleteFlows(List<Integer> ids);

    Map<String, Object> getFlowDetail(Integer id);

    Map<String, Object> getFullFlowDetail(Integer id);

    int createTemplate(Integer swaggerMappingId, String name, String description, String operator, AppSwaggerMapping swaggerMapping);

    List<BrickFlow> listTemplates(BrickFlow query, int offset, int limit, Integer endpointId);

    int countTemplates(BrickFlow query, Integer endpointId);

    BrickFlowRun runFlow(Integer id, String operator, String overrideBaseUrl, Integer runType);

    BrickFlowRun runFlow(Integer id, String operator, String overrideBaseUrl, Integer runType, Map<String, String> customHeaders);

    BrickFlowRun runFlow(Integer id, String operator, String overrideBaseUrl, Integer runType, Map<String, String> customHeaders, Long suiteRunId);

    BrickFlowRun runFlowWithCustomNodeParams(Integer id, String operator, String overrideBaseUrl, Integer runType,
                                              Long nodeId, Integer endpointId, Integer grpcEndpointId, Map<String, Object> customNodeParams);

    Map<String, Object> getRunDetail(Long runId);

    List<Map<String, Object>> listRunsBySwaggerMappingId(Integer swaggerMappingId, String flowName, String flowNameLike,
                                                          String status, String startDate, String endDate,
                                                          Long testSuiteRunId, String batchId, Integer runType,
                                                          Boolean distinctLatest, int offset, int limit);

    int countRunsBySwaggerMappingId(Integer swaggerMappingId, String flowName, String flowNameLike,
                                     String status, String startDate, String endDate,
                                     Long testSuiteRunId, String batchId, Integer runType, Boolean distinctLatest);

    int copyFlow(Integer sourceFlowId, String newName, String targetEnv, String operator);

    int copyFlow(Integer sourceFlowId, String newName, String targetEnv, String operator, Integer targetSwaggerMappingId);

    Map<String, Object> batchCopyFlows(List<FlowCopyItem> flowCopyItems, String defaultTargetEnv, String operator);

    String getNodeFullResponse(Long runId, Long nodeId);

    BrickFlowRunNode getNodeExecutionDetail(Long runId, Long nodeId);

    List<BrickFlowRunNodeAssertion> getRunNodeAssertions(Long runNodeId);
}
