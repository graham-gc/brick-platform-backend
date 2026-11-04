# Flow 保存：前后端契约

> 已对照 2026-11-04 的前后端实现核对。负数临时节点 ID、保存后重新加载、重复接口节点和断言保存均已接入。本文描述当前行为；末尾的页面验收清单需在联调时执行。

浏览器通过 Next.js 的 `/api/brick` 代理访问后端。Spring Boot 的 context path 为 `/api`，Controller 的前缀为 `/brick`，因此直接访问后端时也使用 `/api/brick`。

## 1. 节点身份

- `endpointId` 表示接口定义，不是画布节点身份。
- `BrickFlowNode.id` 表示一次接口调用在当前流程里的节点身份。
- 同一个 `endpointId` 可以在同一个流程中出现多次，每次必须使用不同的节点 `id`。
- 已保存节点使用数据库生成的正数 `flow_node.id`。
- 新拖入且尚未保存的节点使用当前画布内唯一的负数临时 ID。当前前端生成负时间戳形式的值，并保证连续添加时不重复；下文以 `-1` 表示临时 ID。
- 临时 ID 不入库。后端插入节点后会把它转换为数据库 ID。

## 2. 连线身份

- `sourceNodeId` 和 `targetNodeId` 必须引用画布节点的 `BrickFlowNode.id`。
- 禁止使用 `endpointId` 连接节点，否则同一接口在流程中出现多次时无法区分。
- 新节点之间的连线可以直接引用对应的负数临时节点 ID。
- Edge 自身未保存时也可以使用负数临时 `id`；后端保存时会忽略它并生成数据库 ID。
- 更新流程时后端会重建全部连线，因此 edge ID 不稳定。需要保留的是节点引用、handle 和其他连线配置。

## 3. 保存请求

| 操作 | 方法与路径 | 响应 `data` |
| --- | --- | --- |
| 新建流程 | `POST /api/brick/flows` | 新建的 `BrickFlow`，包含数据库生成的 `id` |
| 更新流程 | `POST /api/brick/flows/update` | 流程主表更新行数，类型为整数；不包含节点 ID 映射 |
| 读取完整画布 | `GET /api/brick/flows/{id}` | `{ flow, nodes, edges }`，node 中包含 `assertions` |

统一响应结构为 `{ "success": true, "message": null, "data": ... }`。保存失败时检查 HTTP 状态和 `success`，不要仅凭 HTTP 请求完成就更新画布状态。

请求体为 `{ flow, nodes, edges, operator }`。前三个字段必填，`nodes` 和 `edges` 可以是空数组。保存是**完整画布替换**，不是局部补丁：

- 更新时先读取完整流程，再合并改动。`flow.id` 必填，并应保留流程其他字段以及最新的 `sharedHeadersJson`、`viewportX`、`viewportY` 和 `viewportZoom`。
- `nodes` 包含全部需要保留的节点。已有节点沿用 ID，新节点使用负数 ID；未提交的原节点会被软删除。
- `edges` 包含全部需要保留的连线。提交空数组会清空连线。
- 节点的 `assertions` 是完整替换列表。省略或传空数组会清除该节点原有断言；断言定义的 ID 会重新生成，不应依赖其稳定性。
- `headersJson`、`payloadJson`、`queryParamsJson`、`pathVarsJson`、`responseVariablesJson` 和 `requestVariableBindingsJson` 仍是 JSON **字符串**，不是嵌套对象或数组。

以下是更新请求的简化示例，重点展示接口复用和临时 ID。ID 均为示例；实际更新应保留读取到的完整流程及节点配置。

```json
{
  "flow": { "id": 12, "name": "Create and pay order" },
  "nodes": [
    { "id": 101, "endpointId": 25, "nodeType": "http", "joinMode": "ALL", "x": 100, "y": 100, "assertions": [] },
    { "id": -1, "endpointId": 25, "nodeType": "http", "joinMode": "ALL", "x": 360, "y": 100, "assertions": [] }
  ],
  "edges": [
    { "id": -1, "sourceNodeId": 101, "targetNodeId": -1 }
  ],
  "operator": "graham"
}
```

这个例子中，接口定义 `25` 被引用两次：已保存节点 `101` 和新节点 `-1` 是两个独立的执行步骤。

节点断言示例：

```json
{
  "assertionType": "status_code",
  "operator": "equals",
  "expectedValue": "200",
  "isEnabled": 1
}
```

当前断言类型为 `status_code`、`json_path`、`header` 和 `response_time`。JSONPath 和 header 断言还需提供 `fieldPath`。`expectedValue` 使用字符串，断言仅在 `isEnabled` 为 `1` 时执行。

## 4. 保存成功后的处理

当前前端已经实现以下流程：

1. 新建页提交完整画布，读取响应 `data.id`，然后跳转到 `/flows/{id}` 加载持久化数据。
2. 编辑页先提交更新，再调用 `GET /api/brick/flows/{id}`。
3. 用读取结果更新查询缓存并重新挂载设计器，替换临时节点 ID、连线和断言。
4. 保存按钮在整个 mutation（包含重新读取）期间显示 loading，重新读取完成后才提示保存成功。
5. 画布存在未保存改动时，Run 按钮不可用；执行使用后端保存的数据。

不要在浏览器中推算自增 ID，也不要把更新接口返回的行数当成 flow ID。若更新已成功但随后的读取失败，应先重新读取流程并恢复画布，再重试编辑，避免重复提交带临时 ID 的旧请求。

## 5. 校验、事务与兼容行为

- 后端仍兼容旧客户端提交的、数据库中不存在的正数临时节点 ID。当前前端已使用负数，新接入方也应使用负数。
- 如果提交了重复的客户端节点 ID，后端会拒绝保存。
- 更新流程时，如果提交的正数节点 ID 已存在但属于其他流程，后端会拒绝保存。
- 如果 edge 引用了本次 nodes 中不存在的节点，后端会拒绝保存并回滚事务。
- 后端校验响应变量名称、JSONPath、绑定目标和共享 headers；变量名称在同一流程内必须唯一，绑定的变量必须已定义。
- 新建和更新均在事务内进行。节点或连线保存抛出异常时，同次保存中的修改回滚。
- 保存契约不代表所有图都能执行。环和无法执行的图在运行阶段仍可能报错；当前执行器仅支持 `http` 节点。

## 6. 页面联调验收清单

- [ ] 将同一个 endpoint 连续添加三次，确认临时节点 ID 各不相同，串联后保存。
- [ ] 刷新流程，确认三个节点获得不同的正数 ID，且 `endpointId` 相同。
- [ ] 修改第二个节点的参数和断言，再保存并刷新，确认节点 ID、配置和断言符合预期。
- [ ] 删除第二个节点及相关连线后保存，确认其余节点 ID 保持不变，断言和连线没有残留。
- [ ] 保存过程中不能重复点击保存；完成后执行的画布与保存结果一致。
- [ ] 确认位置、viewport、Flow Headers、变量定义和绑定在刷新后保留。
- [ ] 模拟保存请求失败，确认错误可见且画布保留未保存状态。

## 7. 实现入口

- [FlowUpsertReq](../src/main/java/apiworkflow/dto/FlowUpsertReq.java)：请求结构。
- [BrickController](../src/main/java/apiworkflow/controller/BrickController.java)：保存与读取响应。
- [BrickFlowServiceImpl](../src/main/java/apiworkflow/service/impl/BrickFlowServiceImpl.java)：节点同步、临时 ID 映射、断言替换与连线重建。
- [BrickFlowServiceImplTest](../src/test/java/apiworkflow/service/impl/BrickFlowServiceImplTest.java)：保存和运行相关的后端回归测试。

前端入口为 `src/features/flow-designer/FlowDesigner.tsx`、`src/app/(main)/flows/new/page.tsx` 和 `src/app/(main)/flows/[id]/page.tsx`。前端仓库的 [开发指南](https://github.com/graham-gc/brick-platform-frontend/blob/main/docs/development.md) 说明了画布状态和代理请求。
