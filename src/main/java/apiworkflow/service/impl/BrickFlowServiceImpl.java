package apiworkflow.service.impl;

import apiworkflow.dto.BrickFlowFullNode;
import apiworkflow.entity.*;
import apiworkflow.execution.AssertionExecutor;
import apiworkflow.execution.FlowHttpExecutor;
import apiworkflow.execution.FlowContextEngine;
import apiworkflow.mapper.*;
import apiworkflow.service.IBrickFlowService;
import com.alibaba.fastjson.JSON;
import org.springframework.beans.BeanUtils;
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
    private BrickFlowNodeAssertionMapper nodeAssertionMapper;

    @Autowired
    private BrickFlowRunNodeAssertionMapper runAssertionMapper;

    @Autowired
    private EndpointDefinitionMapper endpointDefinitionMapper;

    @Autowired
    private FlowHttpExecutor flowHttpExecutor;

    @Autowired
    private FlowContextEngine flowContextEngine;

    @Autowired
    private apiworkflow.execution.AssertionExecutor assertionExecutor;

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
    public int createFullFlow(BrickFlow flow, List<BrickFlowFullNode> fullNodes, List<BrickFlowEdge> edges, String operator) {
        flowContextEngine.validateSharedHeaderVariables(flow.getSharedHeadersJson(), fullNodes);
        flow.setIsDeleted(0);
        flow.setCreateBy(operator);
        int result = flowMapper.insert(flow);

        Map<Long, Long> nodeIdMap = insertNewNodesWithAssertions(flow.getId(), fullNodes, operator);
        replaceEdges(flow.getId(), edges, nodeIdMap, operator, false);

        return result;
    }

    @Override
    @Transactional
    public int updateFullFlow(BrickFlow flow, List<BrickFlowFullNode> fullNodes, List<BrickFlowEdge> edges, String operator) {
        if (flow == null || flow.getId() == null) {
            throw new IllegalArgumentException("Flow id is required when updating a flow");
        }

        flowContextEngine.validateSharedHeaderVariables(flow.getSharedHeadersJson(), fullNodes);
        flow.setUpdateBy(operator);
        int result = flowMapper.updateById(flow);

        Map<Long, Long> nodeIdMap = synchronizeNodesWithAssertions(flow.getId(), fullNodes, operator);
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
        flowContextEngine.validateConfiguration(safeNodes);
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

    private Map<Long, Long> insertNewNodesWithAssertions(Integer flowId, List<BrickFlowFullNode> fullNodes, String operator) {
        List<BrickFlowFullNode> safeNodes = fullNodes == null ? Collections.emptyList() : fullNodes;
        flowContextEngine.validateConfiguration(safeNodes);
        validateUniqueClientNodeIds(safeNodes);

        Map<Long, Long> nodeIdMap = new HashMap<>();
        for (BrickFlowFullNode fullNode : safeNodes) {
            Long clientNodeId = fullNode.getId();
            BrickFlowNode node = fullNode;
            prepareNewNode(node, flowId, operator);
            int inserted = nodeMapper.insert(node);
            if (inserted != 1 || node.getId() == null) {
                throw new IllegalStateException("Database did not return an id for a new flow node");
            }
            if (clientNodeId != null) {
                nodeIdMap.put(clientNodeId, node.getId());
            }

            // Save assertions for this node
            if (fullNode.getAssertions() != null && !fullNode.getAssertions().isEmpty()) {
                for (BrickFlowNodeAssertion assertion : fullNode.getAssertions()) {
                    assertion.setNodeId(node.getId());
                    assertion.setId(null);
                    assertion.setCreateTime(new Date());
                    assertion.setUpdateTime(new Date());
                    nodeAssertionMapper.insert(assertion);
                }
            }
        }
        return nodeIdMap;
    }

    /**
     * Keeps persisted node ids stable during edits, inserts new client-side nodes, and soft
     * deletes nodes omitted from the submitted canvas.
     */
    private Map<Long, Long> synchronizeNodesWithAssertions(Integer flowId, List<BrickFlowFullNode> fullNodes, String operator) {
        List<BrickFlowFullNode> safeNodes = fullNodes == null ? Collections.emptyList() : fullNodes;
        flowContextEngine.validateConfiguration(safeNodes);
        validateUniqueClientNodeIds(safeNodes);

        // Get existing nodes and their assertions
        List<BrickFlowNode> existingNodesList = nodeMapper.selectByFlowId(flowId);
        Map<Long, BrickFlowNode> existingNodes = existingNodesList.stream()
                .collect(Collectors.toMap(BrickFlowNode::getId, node -> node));

        Map<Long, Long> nodeIdMap = new HashMap<>();

        // Delete all existing nodes (cascades to assertions via DB or we delete them manually)
        // First delete assertions for all existing nodes
        for (BrickFlowNode existingNode : existingNodesList) {
            nodeAssertionMapper.deleteByNodeId(existingNode.getId());
        }
        nodeMapper.deleteByFlowId(flowId);

        for (BrickFlowFullNode fullNode : safeNodes) {
            Long clientNodeId = fullNode.getId();
            BrickFlowNode node = fullNode;

            if (clientNodeId != null && clientNodeId > 0 && existingNodes.containsKey(clientNodeId)) {
                // Existing node - update it
                node.setFlowId(flowId);
                node.setJoinMode(normalizedJoinMode(node));
                node.setIsDeleted(0);
                node.setUpdateBy(operator);
                nodeMapper.updateById(node);
                nodeIdMap.put(clientNodeId, clientNodeId);
            } else {
                // New node - insert it
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

            // Sync assertions for this node
            if (fullNode.getAssertions() != null && !fullNode.getAssertions().isEmpty()) {
                for (BrickFlowNodeAssertion assertion : fullNode.getAssertions()) {
                    assertion.setNodeId(node.getId());
                    assertion.setId(null);
                    assertion.setCreateTime(new Date());
                    assertion.setUpdateTime(new Date());
                    nodeAssertionMapper.insert(assertion);
                }
            }
        }
        return nodeIdMap;
    }

    private void prepareNewNode(BrickFlowNode node, Integer flowId, String operator) {
        node.setId(null);
        node.setFlowId(flowId);
        node.setJoinMode(normalizedJoinMode(node));
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
            edge.setSourceHandle(valueOrDefault(edge.getSourceHandle(), "output-right"));
            edge.setTargetHandle(valueOrDefault(edge.getTargetHandle(), "input-left"));
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

        // Convert to BrickFlowFullNode with assertions
        List<BrickFlowFullNode> fullNodes = new ArrayList<>();
        for (BrickFlowNode node : nodes) {
            BrickFlowFullNode fullNode = new BrickFlowFullNode();
            BeanUtils.copyProperties(node, fullNode);
            List<BrickFlowNodeAssertion> assertions = nodeAssertionMapper.selectByNodeId(node.getId());
            fullNode.setAssertions(assertions);
            fullNodes.add(fullNode);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("flow", flow);
        result.put("nodes", fullNodes);
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
            List<BrickFlowNode> nodes = nodeMapper.selectByFlowId(id);
            List<BrickFlowEdge> edges = edgeMapper.selectByFlowId(id);
            List<BrickFlowNode> orderedNodes = topologicalOrder(nodes, edges);
            if (orderedNodes.isEmpty()) {
                throw new IllegalStateException("Flow has no executable nodes");
            }
            FlowScheduleResult scheduleResult = executeDag(
                    run.getId(), flow, orderedNodes, edges, overrideBaseUrl, customHeaders);
            finalStatus = scheduleResult.failedCount == 0 && scheduleResult.blockedCount == 0
                    ? "success" : "failed";
            errorMessage = scheduleResult.errorMessage();
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

    private FlowScheduleResult executeDag(Long runId, BrickFlow flow, List<BrickFlowNode> nodes,
                                          List<BrickFlowEdge> edges, String overrideBaseUrl,
                                          Map<String, String> customHeaders) {
        Map<Long, List<Long>> incoming = new HashMap<>();
        Map<Long, String> states = new HashMap<>();
        for (BrickFlowNode node : nodes) {
            incoming.put(node.getId(), new ArrayList<Long>());
            states.put(node.getId(), "pending");
        }
        for (BrickFlowEdge edge : edges == null ? Collections.<BrickFlowEdge>emptyList() : edges) {
            incoming.get(edge.getTargetNodeId()).add(edge.getSourceNodeId());
        }

        List<BrickFlowNode> pending = new ArrayList<>(nodes);
        pending.sort(Comparator.comparing(BrickFlowNode::getId));
        Map<String, Object> context = new LinkedHashMap<>();
        FlowScheduleResult result = new FlowScheduleResult();
        while (!pending.isEmpty()) {
            boolean progressed = false;
            Iterator<BrickFlowNode> iterator = pending.iterator();
            while (iterator.hasNext()) {
                BrickFlowNode node = iterator.next();
                NodeReadiness readiness = readiness(node, incoming.get(node.getId()), states);
                if (readiness == NodeReadiness.WAITING) {
                    continue;
                }

                BrickFlowRunNode runNode;
                if (readiness == NodeReadiness.BLOCKED) {
                    runNode = terminalRunNode(runId, node, "blocked",
                            "No incoming dependency can satisfy the "
                                    + normalizedJoinMode(node) + " join strategy");
                    result.blockedCount++;
                } else {
                    try {
                        flowContextEngine.applyBindings(node, context);
                        String effectiveSharedHeaders = flowContextEngine.resolveSharedHeaders(
                                flow.getSharedHeadersJson(), context);
                        runNode = executeNode(runId, flow, node, overrideBaseUrl,
                                customHeaders, effectiveSharedHeaders);
                        if ("success".equalsIgnoreCase(runNode.getStatus())) {
                            flowContextEngine.captureResponseVariables(node, runNode.getFullResponse(), context);
                        }
                    } catch (Exception e) {
                        runNode = failedRunNode(runId, node, rootMessage(e));
                    }
                    if (!"success".equalsIgnoreCase(runNode.getStatus())) {
                        runNode.setStatus("failed");
                        result.failedCount++;
                    }
                }
                runNodeMapper.insert(runNode);

                // Execute assertions after runNode is inserted (so we have runNode.id)
                if (assertionExecutor != null && runNode.getId() != null) {
                    try {
                        Map<String, List<String>> responseHeaders = JSON.parseObject(runNode.getResponseHeaders(),
                                new com.alibaba.fastjson.TypeReference<Map<String, List<String>>>() {});
                        AssertionExecutor.AssertionResult assertionResult = assertionExecutor.executeAssertions(
                                runNode.getId(), node.getId(),
                                runNode.getHttpStatus(), runNode.getFullResponse(),
                                responseHeaders,
                                runNode.getDurationMs());
                        runNode.setAssertionTotalCount(assertionResult.total);
                        runNode.setAssertionPassedCount(assertionResult.passed);
                        runNode.setAssertionFailedCount(assertionResult.failed);
                        StringBuilder summary = new StringBuilder();
                        for (apiworkflow.entity.BrickFlowRunNodeAssertion ra : assertionResult.runAssertions) {
                            if (summary.length() > 0) summary.append("; ");
                            summary.append(ra.getStatus().toUpperCase()).append(":")
                                   .append(ra.getActualValue()).append(" ")
                                   .append(ra.getExpectedValue());
                        }
                        runNode.setAssertionSummary(summary.toString());
                        runNodeMapper.updateById(runNode);
                    } catch (Exception e) {
                        // Assertion execution should not fail the node
                    }
                }

                states.put(node.getId(), runNode.getStatus().toLowerCase(Locale.ROOT));
                result.record(node, runNode);
                iterator.remove();
                progressed = true;
            }
            if (!progressed) {
                throw new IllegalStateException("Flow scheduler cannot resolve the remaining nodes");
            }
        }
        return result;
    }

    private NodeReadiness readiness(BrickFlowNode node, List<Long> parentIds, Map<Long, String> states) {
        if (parentIds == null || parentIds.isEmpty()) {
            return NodeReadiness.READY;
        }
        boolean anySuccess = parentIds.stream().anyMatch(id -> "success".equals(states.get(id)));
        boolean allSuccess = parentIds.stream().allMatch(id -> "success".equals(states.get(id)));
        boolean allTerminal = parentIds.stream().allMatch(id -> isTerminal(states.get(id)));

        if ("ANY".equals(normalizedJoinMode(node))) {
            if (anySuccess) {
                return NodeReadiness.READY;
            }
            return allTerminal ? NodeReadiness.BLOCKED : NodeReadiness.WAITING;
        }
        if (allSuccess) {
            return NodeReadiness.READY;
        }
        return allTerminal || parentIds.stream().anyMatch(id -> isFailureState(states.get(id)))
                ? NodeReadiness.BLOCKED : NodeReadiness.WAITING;
    }

    private boolean isTerminal(String state) {
        return "success".equals(state) || isFailureState(state) || "skipped".equals(state);
    }

    private boolean isFailureState(String state) {
        return "failed".equals(state) || "blocked".equals(state);
    }

    private String normalizedJoinMode(BrickFlowNode node) {
        return "ANY".equalsIgnoreCase(node.getJoinMode()) ? "ANY" : "ALL";
    }

    private BrickFlowRunNode executeNode(Long runId, BrickFlow flow, BrickFlowNode node,
                                         String overrideBaseUrl, Map<String, String> customHeaders,
                                         String effectiveSharedHeadersJson) {
        try {
            if (!"http".equalsIgnoreCase(valueOrDefault(node.getNodeType(), "http"))) {
                return failedRunNode(runId, node, "Unsupported node type: " + node.getNodeType());
            }
            if (node.getEndpointId() == null) {
                return failedRunNode(runId, node, "HTTP node has no endpoint id");
            }
            EndpointDefinition endpoint = endpointDefinitionMapper.selectById(node.getEndpointId());
            if (endpoint == null || Integer.valueOf(1).equals(endpoint.getIsDeleted())) {
                return failedRunNode(runId, node,
                        "Endpoint not found or deleted: " + node.getEndpointId());
            }
            return flowHttpExecutor.execute(
                    runId, flow, node, endpoint, overrideBaseUrl, customHeaders,
                    effectiveSharedHeadersJson);
        } catch (Exception e) {
            return failedRunNode(runId, node, rootMessage(e));
        }
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
        return terminalRunNode(runId, node, "failed", errorMessage);
    }

    private BrickFlowRunNode terminalRunNode(Long runId, BrickFlowNode node, String status, String errorMessage) {
        Date now = new Date();
        BrickFlowRunNode runNode = new BrickFlowRunNode();
        runNode.setRunId(runId);
        runNode.setNodeId(node.getId());
        runNode.setEndpointId(node.getEndpointId());
        runNode.setGrpcEndpointId(node.getGrpcEndpointId());
        runNode.setStatus(status);
        runNode.setStartTime(now);
        runNode.setEndTime(now);
        runNode.setDurationMs(0L);
        runNode.setErrorMsg(errorMessage);
        runNode.setAssertionTotalCount(0);
        runNode.setAssertionPassedCount(0);
        runNode.setAssertionFailedCount(0);
        return runNode;
    }

    private enum NodeReadiness {
        READY,
        WAITING,
        BLOCKED
    }

    private static class FlowScheduleResult {
        private int failedCount;
        private int blockedCount;
        private final List<String> failures = new ArrayList<>();

        private void record(BrickFlowNode node, BrickFlowRunNode runNode) {
            if ("failed".equals(runNode.getStatus())) {
                failures.add("Node " + node.getId() + " failed: " + runNode.getErrorMsg());
            }
        }

        private String errorMessage() {
            if (failures.isEmpty()) {
                return blockedCount == 0
                        ? null
                        : blockedCount + " " + nodeLabel(blockedCount)
                                + " blocked because their dependencies were not satisfied";
            }
            String message = String.join("; ", failures);
            if (blockedCount > 0) {
                message += "; " + blockedCount + " downstream " + nodeLabel(blockedCount)
                        + " not executed";
            }
            return message;
        }

        private String nodeLabel(int count) {
            return count == 1 ? "node was" : "nodes were";
        }
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

        // Load assertions for each node and enrich with original config
        for (BrickFlowRunNode node : nodes) {
            List<BrickFlowRunNodeAssertion> assertions = runAssertionMapper.selectByRunNodeId(node.getId());
            // Enrich with original assertion config
            for (BrickFlowRunNodeAssertion assertion : assertions) {
                if (assertion.getAssertionId() != null) {
                    BrickFlowNodeAssertion original = nodeAssertionMapper.selectById(assertion.getAssertionId());
                    if (original != null) {
                        assertion.setAssertionType(original.getAssertionType());
                        assertion.setFieldPath(original.getFieldPath());
                        assertion.setOperator(original.getOperator());
                    }
                }
            }
            node.setAssertions(assertions);
        }

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
        return runAssertionMapper.selectByRunNodeId(runNodeId);
    }

    @Override
    public List<BrickFlowNodeAssertion> getAssertionsByNodeId(Long nodeId) {
        return nodeAssertionMapper.selectByNodeId(nodeId);
    }

    @Override
    @Transactional
    public int replaceAssertions(Long nodeId, List<BrickFlowNodeAssertion> assertions, String operator) {
        nodeAssertionMapper.deleteByNodeId(nodeId);
        if (assertions == null || assertions.isEmpty()) {
            return 0;
        }
        for (BrickFlowNodeAssertion assertion : assertions) {
            assertion.setNodeId(nodeId);
            assertion.setId(null);
            assertion.setCreateTime(new Date());
            assertion.setUpdateTime(new Date());
            nodeAssertionMapper.insert(assertion);
        }
        return assertions.size();
    }

    @Override
    @Transactional
    public int deleteAssertions(Long nodeId) {
        return nodeAssertionMapper.deleteByNodeId(nodeId);
    }
}
