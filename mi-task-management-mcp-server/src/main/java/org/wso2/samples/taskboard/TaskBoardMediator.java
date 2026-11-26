/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.samples.taskboard;

import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.AbstractMediator;

/**
 * Runs one operation on the task board. The API resource names the operation and puts its inputs
 * in variables (taskId, status, title, priority, assignee); the mediator replaces the payload with
 * the result and sets the HTTP status, so a refused request reaches an MCP client as a tool error
 * carrying the board's own message.
 */
public class TaskBoardMediator extends AbstractMediator {

    private String operation;

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getOperation() {
        return operation;
    }

    @Override
    public boolean mediate(MessageContext context) {
        int status = 200;
        String body;
        try {
            body = run(context);
            if ("create".equals(operation)) {
                status = 201;
            }
        } catch (TaskBoardException e) {
            status = e.getHttpStatus();
            body = "{\"error\":" + Json.quote(e.getMessage()) + "}";
        }
        respondWith(context, status, body);
        return true;
    }

    private String run(MessageContext context) throws TaskBoardException {
        if (operation == null) {
            throw new SynapseException("TaskBoardMediator needs an operation");
        }
        switch (operation) {
            case "list":
                return TaskBoard.list(variable(context, "status"));
            case "create":
                return TaskBoard.create(variable(context, "title"), variable(context, "priority"),
                        variable(context, "assignee"));
            case "setStatus":
                return TaskBoard.setStatus(variable(context, "taskId"), variable(context, "status"));
            case "assign":
                return TaskBoard.assign(variable(context, "taskId"), variable(context, "assignee"));
            default:
                throw new SynapseException("TaskBoardMediator has no operation '" + operation + "'");
        }
    }

    private static String variable(MessageContext context, String name) {
        Object value = context.getVariable(name);
        return value == null ? null : value.toString();
    }

    private static void respondWith(MessageContext context, int status, String body) {
        org.apache.axis2.context.MessageContext axis2 = ((Axis2MessageContext) context).getAxis2MessageContext();
        try {
            JsonUtil.getNewJsonPayload(axis2, body, true, true);
        } catch (Exception e) {
            throw new SynapseException("Could not set the task board's response", e);
        }
        // A GET arrives with no entity body, and MI would otherwise answer it with none too.
        axis2.removeProperty("NO_ENTITY_BODY");
        axis2.setProperty("messageType", "application/json");
        axis2.setProperty("ContentType", "application/json");
        axis2.setProperty("HTTP_SC", status);
    }
}
