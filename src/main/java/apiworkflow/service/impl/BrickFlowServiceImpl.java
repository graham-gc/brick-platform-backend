package apiworkflow.service.impl;

import apiworkflow.dto.BrickFlowFullNode;
import apiworkflow.entity.*;
import apiworkflow.execution.FlowHttpExecutor;
import apiworkflow.mapper.*;
import apiworkflow.service.IBrickFlowService;
import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class BrickFlowServiceImpl implements IBrickFlowService {

    @Autowired
    private BrickFlowMapper flowMapper;

    @Autowired
    private BrickFlowNodeMapper nodeMapper;

    @Autowired
    private BrickFlowEdgeMapper edgeMapper;

    @Autowired
    private BrickFlowRunMapper runMapper;

    @Autowired
    private BrickFlowRunNodeMapper runNodeMapper;

    @Autowired
    private EndpointDefinitionMapper endpointDefinitionMapper;

    @Autowired
    private FlowHttpExecutor flowHttpExecutor;

    @Override
    public BrickFlow getFlow(Integer id) {
        return flowMapper.selectById(id);
    }

    @Override
    public List<BrickFlow> listFlows(BrickFlow query, int offset, int limit,
                                      Integer endpointId, Integer grpcEndpointId, String executionStatus) {
        if (endpointId != null) {
            return flowMapper.selectPageByEndpoint(query, offset, limit, endpointId, executionStatus);
        }
        return flowMapper.selectPage(query, offset, limit, executionStatus);
    }

    @Override
    public int countFlows(BrickFlow query, Integer endpointId, Integer grpcEndpointId, String executionStatus) {
        return flowMapper.count(query, executionStatus);
    }

    @Override
    @Transactional
    public int createFlow(BrickFlow flow, List<BrickFlowNode> nodes, List<BrickFlowEdge> edges, String operator) {
        flow.setIsDeleted(0);
        flow.setCreateBy(operator);
        int result = flowMapper.insert(flow);

        Map<Long, Long> nodeIdMap = insertNewNodes(flow.getId(), nodes, operator);
        replaceEdges(flow.getId(), edges, nodeIdMap, operator, false);

        return result;
    }

    @Override
    @Transactional
    public int createFullFlow(BrickFlow flow, List<BrickFlowFullNode> fullNodes, List<BrickFlowEdge> edges, String operator) {
        flow.setIsDeleted(0);
        flow.setCreateBy(operator);
        int result = flowMapper.insert(flow);

        List<BrickFlowNode> nodes = fullNodes == null
                ? Collections.emptyList()
                : new ArrayList<>(fullNodes);
        Map<Long, Long> nodeIdMap = insertNewNodes(flow.getId(), nodes, operator);
        replaceEdges(flow.getId(), edges, nodeIdMap, operator, false);

        return result;
    }

    @Override
    @Transactional
    public int updateFlow(BrickFlow flow, List<BrickFlowNode> nodes, List<BrickFlowEdge> edges, String operator) {
        if (flow == null || flow.getId() == null) {
            throw new IllegalArgumentException("Flow id is required when updating a flow");
        }

        flow.setUpdateBy(operator);
        int result = flowMapper.updateById(flow);

        Map<Long, Long> nodeIdMap = synchronizeNodes(flow.getId(), nodes, operator);
        replaceEdges(flow.getId(), edges, nodeIdMap, operator, true);

        return result;
    }

    /**
     * Inserts every canvas node as an independent flow_node row and returns the mapping from
     * the client-side node reference to the generated database id. endpoint_id is deliberately
     * not used as an identity because the same endpoint may occur multiple times in one flow.
     */
    private Map<Long, Long> insertNewNodes(Integer flowId, List<? extends BrickFlowNode> nodes, String operator) {
        List<? extends BrickFlowNode> safeNodes = nodes == null ? Collections.emptyList() : nodes;
        validateUniqueClientNodeIds(safeNodes);

        Map<Long, Long> nodeIdMap = new HashMap<>();
        for (BrickFlowNode node : safeNodes) {
            Long clientNodeId = node.getId();
            prepareNewNode(node, flowId, operator);
            int inserted = nodeMapper.insert(node);
            if (inserted != 1 || node.getId() == null) {
                throw new IllegalStateException("Database did not return an id for a new flow node");
            }
            if (clientNodeId != null) {
                nodeIdMap.put(clientNodeId, node.getId());
            }
        }
        return nodeIdMap;
    }

    /**
     * Keeps persisted node ids stable during edits, inserts new client-side nodes, and soft
     * deletes nodes omitted from the submitted canvas.
     */
    private Map<Long, Long> synchronizeNodes(Integer flowId, List<BrickFlowNode> nodes, String operator) {
        List<BrickFlowNode> safeNodes = nodes == null ? Collections.emptyList() : nodes;
        validateUniqueClientNodeIds(safeNodes);

        Map<Long, BrickFlowNode> existingNodes = nodeMapper.selectByFlowId(flowId).stream()
                .collect(Collectors.toMap(BrickFlowNode::getId, node -> node));
        Map<Long, Long> nodeIdMap = new HashMap<>();

        nodeMapper.deleteByFlowId(flowId);
        for (BrickFlowNode node : safeNodes) {
            Long clientNodeId = node.getId();
            if (clientNodeId != null && existingNodes.containsKey(clientNodeId)) {
                node.setFlowId(flowId);
                node.setIsDeleted(0);
                node.setUpdateBy(operator);
                nodeMapper.updateById(node);
                nodeIdMap.put(clientNodeId, clientNodeId);
                continue;
            }

            if (clientNodeId != null && clientNodeId > 0 && nodeMapper.selectById(clientNodeId) != null) {
                throw new IllegalArgumentException(
                        "Node " + clientNodeId + " does not belong to active flow " + flowId);
            }

            prepareNewNode(node, flowId, operator);
            int inserted = nodeMapper.insert(node);
            if (inserted != 1 || node.getId() == null) {
                throw new IllegalStateException("Database did not return an id for a new flow node");
            }
            if (clientNodeId != null) {
                nodeIdMap.put(clientNodeId, node.getId());
            }
        }
        return nodeIdMap;
    }

    private void prepareNewNode(BrickFlowNode node, Integer flowId, String operator) {
        node.setId(null);
        node.setFlowId(flowId);
        node.setIsDeleted(0);
        node.setCreateBy(operator);
        node.setUpdateBy(null);
    }

    private void validateUniqueClientNodeIds(List<? extends BrickFlowNode> nodes) {
        Set<Long> clientNodeIds = new HashSet<>();
        for (BrickFlowNode node : nodes) {
            if (node == null) {
                throw new IllegalArgumentException("Flow nodes must not contain null entries");
            }
            Long clientNodeId = node.getId();
            if (clientNodeId != null && !clientNodeIds.add(clientNodeId)) {
                throw new IllegalArgumentException("Duplicate client node id: " + clientNodeId);
            }
        }
    }

    private void replaceEdges(Integer flowId, List<BrickFlowEdge> edges,
                              Map<Long, Long> nodeIdMap, String operator, boolean deleteExisting) {
        if (deleteExisting) {
            edgeMapper.deleteByFlowId(flowId);
        }
        if (edges == null || edges.isEmpty()) {
            return;
        }

        for (BrickFlowEdge edge : edges) {
            if (edge == null) {
                throw new IllegalArgumentException("Flow edges must not contain null entries");
            }
            edge.setId(null);
            edge.setFlowId(flowId);
            edge.setSourceNodeId(resolveNodeId(edge.getSourceNodeId(), nodeIdMap, "source"));
            edge.setTargetNodeId(resolveNodeId(edge.getTargetNodeId(), nodeIdMap, "target"));
            edge.setIsDeleted(0);
            edge.setCreateBy(operator);
            edge.setUpdateBy(null);
        }
        edgeMapper.batchInsert(edges);
    }

    private Long resolveNodeId(Long clientNodeId, Map<Long, Long> nodeIdMap, String edgeEnd) {
        if (clientNodeId == null) {
            throw new IllegalArgumentException("Edge " + edgeEnd + " node id is required");
        }
        Long databaseNodeId = nodeIdMap.get(clientNodeId);
        if (databaseNodeId == null) {
            throw new IllegalArgumentException(
                    "Edge " + edgeEnd + " references a node not present in the submitted flow: " + clientNodeId);
        }
        return databaseNodeId;
    }

    @Override
    public int deleteFlow(Integer id, String operator) {
        return flowMapper.softDeleteById(id, operator);
    }

    @Override
    public int hardDeleteFlows(List<Integer> ids) {
        return flowMapper.hardDeleteByIds(ids);
    }

    @Override
    public Map<String, Object> getFlowDetail(Integer id) {
        BrickFlow flow = flowMapper.selectById(id);
        List<BrickFlowNode> nodes = nodeMapper.selectByFlowId(id);
        List<BrickFlowEdge> edges = edgeMapper.selectByFlowId(id);

        Map<String, Object> result = new HashMap<>();
        result.put("flow", flow);
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    @Override
    public Map<String, Object> getFullFlowDetail(Integer id) {
        return getFlowDetail(id);
    }

    @Override
    @Transactional
    public int createTemplate(Integer swaggerMappingId, String name, String description, String operator, AppSwaggerMapping swaggerMapping) {
        BrickFlow template = new BrickFlow();
        template.setName(name);
        template.setEnv(swaggerMapping.getEnv());
        template.setSwaggerMappingId(swaggerMappingId);
        template.setAppConfigId(swaggerMapping.getAppConfigId());
        template.setDescription(description);
        template.setStatus("draft");
        template.setVersion(0);
        template.setFlowTemplateId(-1);
        template.setIsDeleted(0);
        template.setSource(0);
        template.setCreateBy(operator);
        return flowMapper.insert(template);
    }

    @Override
    public List<BrickFlow> listTemplates(BrickFlow query, int offset, int limit, Integer endpointId) {
        return flowMapper.selectTemplatesBySwaggerMappingId(
                query.getSwaggerMappingId(), query.getName(), offset, limit);
    }

    @Override
    public int countTemplates(BrickFlow query, Integer endpointId) {
        return flowMapper.countTemplatesBySwaggerMappingId(query.getSwaggerMappingId(), query.getName());
    }

    @Override
    public BrickFlowRun runFlow(Integer id, String operator, String overrideBaseUrl, Integer runType) {
        return runFlow(id, operator, overrideBaseUrl, runType, null, null);
    }

    @Override
    public BrickFlowRun runFlow(Integer id, String operator, String overrideBaseUrl, Integer runType, Map<String, String> customHeaders) {
        return runFlow(id, operator, overrideBaseUrl, runType, customHeaders, null);
    }

    @Override
    public BrickFlowRun runFlow(Integer id, String operator, String overrideBaseUrl, Integer runType,
                                 Map<String, String> customHeaders, Long suiteRunId) {
        BrickFlow flow = flowMapper.selectById(id);
        if (flow == null) {
            throw new RuntimeException("Flow not found: " + id);
        }

        String runOperator = StringUtils.hasText(operator) ? operator : "system";
        BrickFlowRun run = new BrickFlowRun();
        run.setFlowId(id);
        run.setStatus("running");
        run.setTriggeredBy(runOperator);
        run.setRunType(runType != null ? runType : 0);
        run.setOverrideBaseUrl(overrideBaseUrl);
        run.setSuiteRunId(suiteRunId);
        run.setStartTime(new Date());
        run.setCreateBy(runOperator);
        runMapper.insert(run);
        if (run.getId() == null) {
            throw new IllegalStateException("Database did not return an id for the flow run");
        }

        String finalStatus = "success";
        String errorMessage = null;
        try {
            List<BrickFlowNode> orderedNodes = topologicalOrder(
                    nodeMapper.selectByFlowId(id), edgeMapper.selectByFlowId(id));
            if (orderedNodes.isEmpty()) {
                throw new IllegalStateException("Flow has no executable nodes");
            }

            for (BrickFlowNode node : orderedNodes) {
                BrickFlowRunNode runNode;
                if (!"http".equalsIgnoreCase(valueOrDefault(node.getNodeType(), "http"))) {
                    runNode = failedRunNode(run.getId(), node, "Unsupported node type: " + node.getNodeType());
                } else if (node.getEndpointId() == null) {
                    runNode = failedRunNode(run.getId(), node, "HTTP node has no endpoint id");
                } else {
                    EndpointDefinition endpoint = endpointDefinitionMapper.selectById(node.getEndpointId());
                    if (endpoint == null || Integer.valueOf(1).equals(endpoint.getIsDeleted())) {
                        runNode = failedRunNode(run.getId(), node,
                                "Endpoint not found or deleted: " + node.getEndpointId());
                    } else {
                        runNode = flowHttpExecutor.execute(
                                run.getId(), flow, node, endpoint, overrideBaseUrl, customHeaders);
                    }
                }
                runNodeMapper.insert(runNode);
                if (!"success".equals(runNode.getStatus())) {
                    finalStatus = "failed";
                    errorMessage = "Node " + node.getId() + " failed: " + runNode.getErrorMsg();
                    break;
                }
            }
        } catch (Exception e) {
            finalStatus = "failed";
            errorMessage = rootMessage(e);
        }

        Date endTime = new Date();
        long durationMs = endTime.getTime() - run.getStartTime().getTime();
        runMapper.updateStatusAndDuration(run.getId(), finalStatus, durationMs, errorMessage);
        run.setStatus(finalStatus);
        run.setDurationMs(durationMs);
        run.setErrorMsg(errorMessage);
        run.setEndTime(endTime);
        return run;
    }

    private List<BrickFlowNode> topologicalOrder(List<BrickFlowNode> nodes, List<BrickFlowEdge> edges) {
        Map<Long, BrickFlowNode> nodesById = new HashMap<>();
        Map<Long, Integer> incomingCount = new HashMap<>();
        Map<Long, List<Long>> outgoing = new HashMap<>();
        for (BrickFlowNode node : nodes == null ? Collections.<BrickFlowNode>emptyList() : nodes) {
            if (node.getId() == null) {
                throw new IllegalStateException("Persisted flow node has no id");
            }
            nodesById.put(node.getId(), node);
            incomingCount.put(node.getId(), 0);
            outgoing.put(node.getId(), new ArrayList<Long>());
        }
        for (BrickFlowEdge edge : edges == null ? Collections.<BrickFlowEdge>emptyList() : edges) {
            Long source = edge.getSourceNodeId();
            Long target = edge.getTargetNodeId();
            if (!nodesById.containsKey(source) || !nodesById.containsKey(target)) {
                throw new IllegalStateException("Flow edge references a missing node");
            }
            outgoing.get(source).add(target);
            incomingCount.put(target, incomingCount.get(target) + 1);
        }

        PriorityQueue<Long> ready = new PriorityQueue<>();
        for (Map.Entry<Long, Integer> entry : incomingCount.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<BrickFlowNode> ordered = new ArrayList<>(nodesById.size());
        while (!ready.isEmpty()) {
            Long nodeId = ready.poll();
            ordered.add(nodesById.get(nodeId));
            for (Long target : outgoing.get(nodeId)) {
                int remaining = incomingCount.get(target) - 1;
                incomingCount.put(target, remaining);
                if (remaining == 0) {
                    ready.add(target);
                }
            }
        }
        if (ordered.size() != nodesById.size()) {
            throw new IllegalStateException("Flow contains a cycle");
        }
        return ordered;
    }

    private BrickFlowRunNode failedRunNode(Long runId, BrickFlowNode node, String errorMessage) {
        Date now = new Date();
        BrickFlowRunNode runNode = new BrickFlowRunNode();
        runNode.setRunId(runId);
        runNode.setNodeId(node.getId());
        runNode.setEndpointId(node.getEndpointId());
        runNode.setGrpcEndpointId(node.getGrpcEndpointId());
        runNode.setStatus("failed");
        runNode.setStartTime(now);
        runNode.setEndTime(now);
        runNode.setDurationMs(0L);
        runNode.setErrorMsg(errorMessage);
        runNode.setAssertionTotalCount(0);
        runNode.setAssertionPassedCount(0);
        runNode.setAssertionFailedCount(0);
        return runNode;
    }

    private String valueOrDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return StringUtils.hasText(current.getMessage())
                ? current.getMessage() : current.getClass().getSimpleName();
    }

    @Override
    public BrickFlowRun runFlowWithCustomNodeParams(Integer id, String operator, String overrideBaseUrl, Integer runType,
                                                     Long nodeId, Integer endpointId, Integer grpcEndpointId,
                                                     Map<String, Object> customNodeParams) {
        return runFlow(id, operator, overrideBaseUrl, runType);
    }

    @Override
    public Map<String, Object> getRunDetail(Long runId) {
        BrickFlowRun run = runMapper.selectById(runId);
        List<BrickFlowRunNode> nodes = runNodeMapper.selectByRunId(runId);

        Map<String, Object> result = new HashMap<>();
        result.put("run", run);
        result.put("nodes", nodes);
        return result;
    }

    @Override
    public List<Map<String, Object>> listRunsBySwaggerMappingId(Integer swaggerMappingId, String flowName,
                                                                   String flowNameLike, String status,
                                                                   String startDate, String endDate,
                                                                   Long testSuiteRunId, String batchId,
                                                                   Integer runType, Boolean distinctLatest,
                                                                   int offset, int limit) {
        return runMapper.selectRunsBySwaggerMappingId(
                swaggerMappingId, flowName, flowNameLike, status, startDate, endDate,
                testSuiteRunId, batchId, runType, distinctLatest, offset, limit);
    }

    @Override
    public int countRunsBySwaggerMappingId(Integer swaggerMappingId, String flowName, String flowNameLike,
                                            String status, String startDate, String endDate,
                                            Long testSuiteRunId, String batchId, Integer runType,
                                            Boolean distinctLatest) {
        return runMapper.countRunsBySwaggerMappingId(
                swaggerMappingId, flowName, flowNameLike, status, startDate, endDate,
                testSuiteRunId, batchId, runType, distinctLatest);
    }

    @Override
    @Transactional
    public int copyFlow(Integer sourceFlowId, String newName, String targetEnv, String operator) {
        return copyFlow(sourceFlowId, newName, targetEnv, operator, null);
    }

    @Override
    @Transactional
    public int copyFlow(Integer sourceFlowId, String newName, String targetEnv, String operator, Integer targetSwaggerMappingId) {
        BrickFlow source = flowMapper.selectById(sourceFlowId);
        if (source == null) {
            throw new RuntimeException("Source flow not found: " + sourceFlowId);
        }

        BrickFlow newFlow = new BrickFlow();
        newFlow.setName(newName);
        newFlow.setEnv(targetEnv != null ? targetEnv : source.getEnv());
        newFlow.setSwaggerMappingId(targetSwaggerMappingId != null ? targetSwaggerMappingId : source.getSwaggerMappingId());
        newFlow.setAppConfigId(source.getAppConfigId());
        newFlow.setDescription(source.getDescription());
        newFlow.setStatus(source.getStatus());
        newFlow.setVersion(source.getVersion());
        newFlow.setFlowTemplateId(source.getId());
        newFlow.setSource(source.getSource());
        newFlow.setSharedHeadersJson(source.getSharedHeadersJson());
        newFlow.setIsDeleted(0);
        newFlow.setCreateBy(operator);

        int result = flowMapper.insert(newFlow);

        List<BrickFlowNode> nodes = nodeMapper.selectByFlowId(sourceFlowId);
        Map<Long, Long> nodeIdMap = insertNewNodes(newFlow.getId(), nodes, operator);

        List<BrickFlowEdge> edges = edgeMapper.selectByFlowId(sourceFlowId);
        replaceEdges(newFlow.getId(), edges, nodeIdMap, operator, false);

        return result;
    }

    @Override
    public Map<String, Object> batchCopyFlows(List<apiworkflow.dto.FlowCopyItem> flowCopyItems,
                                               String defaultTargetEnv, String operator) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> successList = new ArrayList<>();
        List<Map<String, Object>> failedList = new ArrayList<>();

        for (apiworkflow.dto.FlowCopyItem item : flowCopyItems) {
            try {
                copyFlow(item.getSourceFlowId(), item.getNewFlowName(), defaultTargetEnv, operator);
                Map<String, Object> successItem = new HashMap<>();
                successItem.put("sourceFlowId", item.getSourceFlowId());
                successItem.put("newFlowName", item.getNewFlowName());
                successList.add(successItem);
            } catch (Exception e) {
                Map<String, Object> failedItem = new HashMap<>();
                failedItem.put("sourceFlowId", item.getSourceFlowId());
                failedItem.put("reason", e.getMessage());
                failedList.add(failedItem);
            }
        }

        result.put("successCount", successList.size());
        result.put("failedCount", failedList.size());
        result.put("successList", successList);
        result.put("failedList", failedList);
        return result;
    }

    @Override
    public String getNodeFullResponse(Long runId, Long nodeId) {
        BrickFlowRunNode node = runNodeMapper.selectByRunIdAndNodeId(runId, nodeId);
        return node != null ? node.getFullResponse() : null;
    }

    @Override
    public BrickFlowRunNode getNodeExecutionDetail(Long runId, Long nodeId) {
        return runNodeMapper.selectByRunIdAndNodeId(runId, nodeId);
    }

    @Override
    public List<BrickFlowRunNodeAssertion> getRunNodeAssertions(Long runNodeId) {
        return new ArrayList<>();
    }
}
