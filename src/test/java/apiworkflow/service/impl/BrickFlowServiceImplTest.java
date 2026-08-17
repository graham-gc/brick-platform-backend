package apiworkflow.service.impl;

import apiworkflow.entity.BrickFlow;
import apiworkflow.entity.BrickFlowEdge;
import apiworkflow.entity.BrickFlowNode;
import apiworkflow.entity.BrickFlowRun;
import apiworkflow.entity.BrickFlowRunNode;
import apiworkflow.entity.EndpointDefinition;
import apiworkflow.execution.FlowHttpExecutor;
import apiworkflow.execution.FlowContextEngine;
import apiworkflow.mapper.BrickFlowEdgeMapper;
import apiworkflow.mapper.BrickFlowMapper;
import apiworkflow.mapper.BrickFlowNodeMapper;
import apiworkflow.mapper.BrickFlowRunMapper;
import apiworkflow.mapper.BrickFlowRunNodeMapper;
import apiworkflow.mapper.EndpointDefinitionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrickFlowServiceImplTest {

    @Mock
    private BrickFlowMapper flowMapper;

    @Mock
    private BrickFlowNodeMapper nodeMapper;

    @Mock
    private BrickFlowEdgeMapper edgeMapper;

    @Mock
    private BrickFlowRunMapper runMapper;

    @Mock
    private BrickFlowRunNodeMapper runNodeMapper;

    @Mock
    private EndpointDefinitionMapper endpointDefinitionMapper;

    @Mock
    private FlowHttpExecutor flowHttpExecutor;

    private BrickFlowServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BrickFlowServiceImpl();
        ReflectionTestUtils.setField(service, "flowMapper", flowMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "edgeMapper", edgeMapper);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        ReflectionTestUtils.setField(service, "runNodeMapper", runNodeMapper);
        ReflectionTestUtils.setField(service, "endpointDefinitionMapper", endpointDefinitionMapper);
        ReflectionTestUtils.setField(service, "flowHttpExecutor", flowHttpExecutor);
        ReflectionTestUtils.setField(service, "flowContextEngine", new FlowContextEngine());
    }

    @Test
    void createsIndependentNodesForRepeatedEndpointAndMapsTheirEdges() {
        BrickFlow flow = new BrickFlow();
        flow.setName("repeat endpoint");
        when(flowMapper.insert(any(BrickFlow.class))).thenAnswer(invocation -> {
            ((BrickFlow) invocation.getArgument(0)).setId(12);
            return 1;
        });
        assignGeneratedNodeIdsStartingAt(101L);

        List<BrickFlowNode> nodes = Arrays.asList(
                node(-1L, 25),
                node(-2L, 25),
                node(-3L, 25)
        );
        List<BrickFlowEdge> edges = Arrays.asList(
                edge(-1L, -1L, -2L),
                edge(-2L, -2L, -3L)
        );

        service.createFlow(flow, nodes, edges, "graham");

        assertEquals(Long.valueOf(101L), nodes.get(0).getId());
        assertEquals(Long.valueOf(102L), nodes.get(1).getId());
        assertEquals(Long.valueOf(103L), nodes.get(2).getId());
        assertEquals(Integer.valueOf(25), nodes.get(0).getEndpointId());
        assertEquals(Integer.valueOf(25), nodes.get(1).getEndpointId());
        assertEquals(Integer.valueOf(25), nodes.get(2).getEndpointId());

        ArgumentCaptor<List<BrickFlowEdge>> edgesCaptor = edgeListCaptor();
        verify(edgeMapper).batchInsert(edgesCaptor.capture());
        List<BrickFlowEdge> savedEdges = edgesCaptor.getValue();
        assertEquals(Long.valueOf(101L), savedEdges.get(0).getSourceNodeId());
        assertEquals(Long.valueOf(102L), savedEdges.get(0).getTargetNodeId());
        assertEquals(Long.valueOf(102L), savedEdges.get(1).getSourceNodeId());
        assertEquals(Long.valueOf(103L), savedEdges.get(1).getTargetNodeId());
    }

    @Test
    void updateKeepsExistingNodeIdAndMapsNewTemporaryNode() {
        BrickFlow flow = new BrickFlow();
        flow.setId(12);
        when(flowMapper.updateById(flow)).thenReturn(1);

        BrickFlowNode existing = node(101L, 25);
        existing.setFlowId(12);
        when(nodeMapper.selectByFlowId(12)).thenReturn(Collections.singletonList(existing));
        assignGeneratedNodeIdsStartingAt(102L);

        List<BrickFlowNode> nodes = Arrays.asList(node(101L, 25), node(-1L, 25));
        List<BrickFlowEdge> edges = Collections.singletonList(edge(-1L, 101L, -1L));

        service.updateFlow(flow, nodes, edges, "graham");

        verify(nodeMapper).deleteByFlowId(12);
        verify(nodeMapper).updateById(nodes.get(0));
        assertEquals(Long.valueOf(101L), nodes.get(0).getId());
        assertEquals(Long.valueOf(102L), nodes.get(1).getId());
        assertEquals(Integer.valueOf(0), nodes.get(0).getIsDeleted());

        ArgumentCaptor<List<BrickFlowEdge>> edgesCaptor = edgeListCaptor();
        verify(edgeMapper).batchInsert(edgesCaptor.capture());
        BrickFlowEdge savedEdge = edgesCaptor.getValue().get(0);
        assertEquals(Long.valueOf(101L), savedEdge.getSourceNodeId());
        assertEquals(Long.valueOf(102L), savedEdge.getTargetNodeId());
    }

    @Test
    void copyFlowRemapsEdgesToCopiedNodes() {
        BrickFlow source = new BrickFlow();
        source.setId(12);
        source.setName("source");
        when(flowMapper.selectById(12)).thenReturn(source);
        when(flowMapper.insert(any(BrickFlow.class))).thenAnswer(invocation -> {
            ((BrickFlow) invocation.getArgument(0)).setId(13);
            return 1;
        });

        List<BrickFlowNode> sourceNodes = Arrays.asList(node(101L, 25), node(102L, 25));
        when(nodeMapper.selectByFlowId(12)).thenReturn(sourceNodes);
        assignGeneratedNodeIdsStartingAt(201L);

        BrickFlowEdge sourceEdge = edge(301L, 101L, 102L);
        when(edgeMapper.selectByFlowId(12)).thenReturn(Collections.singletonList(sourceEdge));

        service.copyFlow(12, "copy", "test", "graham", null);

        ArgumentCaptor<List<BrickFlowEdge>> edgesCaptor = edgeListCaptor();
        verify(edgeMapper).batchInsert(edgesCaptor.capture());
        BrickFlowEdge copiedEdge = edgesCaptor.getValue().get(0);
        assertEquals(Integer.valueOf(13), copiedEdge.getFlowId());
        assertEquals(Long.valueOf(201L), copiedEdge.getSourceNodeId());
        assertEquals(Long.valueOf(202L), copiedEdge.getTargetNodeId());
    }

    @Test
    void executesNodesInTopologicalOrderAndCompletesRun() {
        BrickFlow flow = new BrickFlow();
        flow.setId(12);
        when(flowMapper.selectById(12)).thenReturn(flow);
        when(runMapper.insert(any(BrickFlowRun.class))).thenAnswer(invocation -> {
            ((BrickFlowRun) invocation.getArgument(0)).setId(501L);
            return 1;
        });

        BrickFlowNode first = node(101L, 1);
        BrickFlowNode second = node(102L, 2);
        when(nodeMapper.selectByFlowId(12)).thenReturn(Arrays.asList(second, first));
        when(edgeMapper.selectByFlowId(12)).thenReturn(
                Collections.singletonList(edge(301L, 101L, 102L)));

        EndpointDefinition firstEndpoint = endpoint(1);
        EndpointDefinition secondEndpoint = endpoint(2);
        when(endpointDefinitionMapper.selectById(1)).thenReturn(firstEndpoint);
        when(endpointDefinitionMapper.selectById(2)).thenReturn(secondEndpoint);
        when(flowHttpExecutor.execute(eq(501L), eq(flow), any(BrickFlowNode.class),
                any(EndpointDefinition.class), isNull(), isNull(), anyString()))
                .thenAnswer(invocation -> successfulRunNode(invocation.getArgument(2)));

        BrickFlowRun run = service.runFlow(12, "graham", null, 0);

        assertEquals("success", run.getStatus());
        ArgumentCaptor<BrickFlowNode> executionOrder = ArgumentCaptor.forClass(BrickFlowNode.class);
        verify(flowHttpExecutor, times(2)).execute(eq(501L), eq(flow), executionOrder.capture(),
                any(EndpointDefinition.class), isNull(), isNull(), anyString());
        assertEquals(Long.valueOf(101L), executionOrder.getAllValues().get(0).getId());
        assertEquals(Long.valueOf(102L), executionOrder.getAllValues().get(1).getId());
        verify(runNodeMapper, times(2)).insert(any(BrickFlowRunNode.class));
        verify(runMapper).updateStatusAndDuration(eq(501L), eq("success"), any(Long.class), isNull());
    }

    @Test
    void executesEveryBranchAndWaitsForAllParentsAtDefaultJoin() {
        BrickFlow flow = runnableFlow(12, 501L);
        BrickFlowNode root = node(101L, 1);
        BrickFlowNode left = node(102L, 2);
        BrickFlowNode right = node(103L, 3);
        BrickFlowNode join = node(104L, 4);
        when(nodeMapper.selectByFlowId(12)).thenReturn(Arrays.asList(join, right, left, root));
        when(edgeMapper.selectByFlowId(12)).thenReturn(Arrays.asList(
                edge(301L, 101L, 102L),
                edge(302L, 101L, 103L),
                edge(303L, 102L, 104L),
                edge(304L, 103L, 104L)));
        mockSuccessfulEndpoints(1, 2, 3, 4);

        BrickFlowRun run = service.runFlow(flow.getId(), "graham", null, 0);

        assertEquals("success", run.getStatus());
        ArgumentCaptor<BrickFlowNode> executionOrder = ArgumentCaptor.forClass(BrickFlowNode.class);
        verify(flowHttpExecutor, times(4)).execute(eq(501L), eq(flow), executionOrder.capture(),
                any(EndpointDefinition.class), isNull(), isNull(), anyString());
        assertEquals(Arrays.asList(101L, 102L, 103L, 104L), executionOrder.getAllValues().stream()
                .map(BrickFlowNode::getId).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void failedBranchBlocksOnlyItsDependantsAndIndependentBranchContinues() {
        BrickFlow flow = runnableFlow(12, 501L);
        BrickFlowNode root = node(101L, 1);
        BrickFlowNode failing = node(102L, 2);
        BrickFlowNode healthy = node(103L, 3);
        BrickFlowNode blocked = node(104L, 4);
        BrickFlowNode healthyChild = node(105L, 5);
        when(nodeMapper.selectByFlowId(12)).thenReturn(Arrays.asList(root, failing, healthy, blocked, healthyChild));
        when(edgeMapper.selectByFlowId(12)).thenReturn(Arrays.asList(
                edge(301L, 101L, 102L),
                edge(302L, 101L, 103L),
                edge(303L, 102L, 104L),
                edge(304L, 103L, 105L)));
        mockSuccessfulEndpoints(1, 2, 3, 5);
        when(flowHttpExecutor.execute(eq(501L), eq(flow), eq(failing), any(EndpointDefinition.class),
                isNull(), isNull(), anyString()))
                .thenReturn(failedRunNode(failing, "upstream failed"));

        BrickFlowRun run = service.runFlow(flow.getId(), "graham", null, 0);

        assertEquals("failed", run.getStatus());
        assertEquals("Node 102 failed: upstream failed; 1 downstream node was not executed", run.getErrorMsg());
        verify(flowHttpExecutor).execute(eq(501L), eq(flow), eq(healthyChild),
                any(EndpointDefinition.class), isNull(), isNull(), anyString());
        verify(flowHttpExecutor, times(4)).execute(eq(501L), eq(flow), any(BrickFlowNode.class),
                any(EndpointDefinition.class), isNull(), isNull(), anyString());
        ArgumentCaptor<BrickFlowRunNode> records = ArgumentCaptor.forClass(BrickFlowRunNode.class);
        verify(runNodeMapper, times(5)).insert(records.capture());
        BrickFlowRunNode blockedRecord = records.getAllValues().stream()
                .filter(item -> Long.valueOf(104L).equals(item.getNodeId()))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("blocked", blockedRecord.getStatus());
    }

    @Test
    void anyJoinRunsWhenAtLeastOneParentSucceeds() {
        BrickFlow flow = runnableFlow(12, 501L);
        BrickFlowNode successParent = node(101L, 1);
        BrickFlowNode failedParent = node(102L, 2);
        BrickFlowNode join = node(103L, 3);
        join.setJoinMode("ANY");
        when(nodeMapper.selectByFlowId(12)).thenReturn(Arrays.asList(successParent, failedParent, join));
        when(edgeMapper.selectByFlowId(12)).thenReturn(Arrays.asList(
                edge(301L, 101L, 103L), edge(302L, 102L, 103L)));
        mockSuccessfulEndpoints(1, 2, 3);
        when(flowHttpExecutor.execute(eq(501L), eq(flow), eq(failedParent), any(EndpointDefinition.class),
                isNull(), isNull(), anyString()))
                .thenReturn(failedRunNode(failedParent, "expected failure"));

        BrickFlowRun run = service.runFlow(flow.getId(), "graham", null, 0);

        assertEquals("failed", run.getStatus());
        verify(flowHttpExecutor).execute(eq(501L), eq(flow), eq(join),
                any(EndpointDefinition.class), isNull(), isNull(), anyString());
    }

    @Test
    void carriesAnExtractedResponseVariableIntoADownstreamRequestField() {
        BrickFlow flow = runnableFlow(12, 501L);
        BrickFlowNode source = node(101L, 1);
        source.setResponseVariablesJson(
                "[{\"name\":\"orderId\",\"responsePath\":\"$.data.id\"}]");
        BrickFlowNode target = node(102L, 2);
        target.setPathVarsJson("{\"orderId\":\"preview\"}");
        target.setRequestVariableBindingsJson(
                "[{\"variableName\":\"orderId\",\"targetType\":\"PATH\",\"targetPath\":\"orderId\"}]");
        when(nodeMapper.selectByFlowId(12)).thenReturn(Arrays.asList(target, source));
        when(edgeMapper.selectByFlowId(12)).thenReturn(
                Collections.singletonList(edge(301L, 101L, 102L)));
        when(endpointDefinitionMapper.selectById(1)).thenReturn(endpoint(1));
        when(endpointDefinitionMapper.selectById(2)).thenReturn(endpoint(2));
        when(flowHttpExecutor.execute(eq(501L), eq(flow), eq(source), any(EndpointDefinition.class),
                isNull(), isNull(), anyString())).thenAnswer(invocation -> {
            BrickFlowRunNode result = successfulRunNode(source);
            result.setFullResponse("{\"data\":{\"id\":\"ord-2001\"}}");
            return result;
        });
        when(flowHttpExecutor.execute(eq(501L), eq(flow), eq(target), any(EndpointDefinition.class),
                isNull(), isNull(), anyString())).thenAnswer(invocation -> successfulRunNode(target));

        BrickFlowRun run = service.runFlow(flow.getId(), "graham", null, 0);

        assertEquals("success", run.getStatus());
        assertEquals("ord-2001", com.alibaba.fastjson.JSON.parseObject(target.getPathVarsJson())
                .getString("orderId"));
    }

    private BrickFlow runnableFlow(Integer flowId, Long runId) {
        BrickFlow flow = new BrickFlow();
        flow.setId(flowId);
        when(flowMapper.selectById(flowId)).thenReturn(flow);
        when(runMapper.insert(any(BrickFlowRun.class))).thenAnswer(invocation -> {
            ((BrickFlowRun) invocation.getArgument(0)).setId(runId);
            return 1;
        });
        return flow;
    }

    private void mockSuccessfulEndpoints(Integer... endpointIds) {
        for (Integer endpointId : endpointIds) {
            when(endpointDefinitionMapper.selectById(endpointId)).thenReturn(endpoint(endpointId));
        }
        when(flowHttpExecutor.execute(any(Long.class), any(BrickFlow.class), any(BrickFlowNode.class),
                any(EndpointDefinition.class), isNull(), isNull(), anyString()))
                .thenAnswer(invocation -> successfulRunNode(invocation.getArgument(2)));
    }

    private void assignGeneratedNodeIdsStartingAt(long firstId) {
        AtomicLong sequence = new AtomicLong(firstId);
        when(nodeMapper.insert(any(BrickFlowNode.class))).thenAnswer(invocation -> {
            BrickFlowNode node = invocation.getArgument(0);
            node.setId(sequence.getAndIncrement());
            return 1;
        });
    }

    private BrickFlowNode node(Long id, Integer endpointId) {
        BrickFlowNode node = new BrickFlowNode();
        node.setId(id);
        node.setEndpointId(endpointId);
        node.setNodeType("http");
        return node;
    }

    private BrickFlowEdge edge(Long id, Long sourceNodeId, Long targetNodeId) {
        BrickFlowEdge edge = new BrickFlowEdge();
        edge.setId(id);
        edge.setSourceNodeId(sourceNodeId);
        edge.setTargetNodeId(targetNodeId);
        return edge;
    }

    private EndpointDefinition endpoint(Integer id) {
        EndpointDefinition endpoint = new EndpointDefinition();
        endpoint.setId(id);
        endpoint.setHttpMethod("GET");
        endpoint.setFullUrl("http://localhost/endpoint-" + id);
        endpoint.setIsDeleted(0);
        return endpoint;
    }

    private BrickFlowRunNode successfulRunNode(BrickFlowNode node) {
        BrickFlowRunNode result = new BrickFlowRunNode();
        result.setNodeId(node.getId());
        result.setStatus("success");
        return result;
    }

    private BrickFlowRunNode failedRunNode(BrickFlowNode node, String message) {
        BrickFlowRunNode result = new BrickFlowRunNode();
        result.setNodeId(node.getId());
        result.setStatus("failed");
        result.setErrorMsg(message);
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<List<BrickFlowEdge>> edgeListCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
