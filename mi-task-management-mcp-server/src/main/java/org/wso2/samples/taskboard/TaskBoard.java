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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The task board, held in memory so the sample runs with no database and no configuration.
 * Every replica keeps its own copy, and a restart returns to the tasks seeded here.
 */
final class TaskBoard {

    static final List<String> STATUSES = Arrays.asList("open", "in-progress", "done");
    static final List<String> PRIORITIES = Arrays.asList("low", "medium", "high");

    private static final List<Task> TASKS = new ArrayList<>();
    private static int nextId = 1005;

    static {
        TASKS.add(new Task("T-1001", "Draft the release notes", "in-progress", "high", "alex"));
        TASKS.add(new Task("T-1002", "Review the onboarding guide", "open", "medium", "priya"));
        TASKS.add(new Task("T-1003", "Archive last quarter's reports", "open", "low", null));
        TASKS.add(new Task("T-1004", "Renew the staging certificate", "done", "high", "sam"));
    }

    private TaskBoard() {
    }

    /** The whole board, or only the tasks in one status when status is not blank. */
    static synchronized String list(String status) throws TaskBoardException {
        String filter = blankToNull(status);
        if (filter != null) {
            requireOneOf("status", filter, STATUSES);
        }
        StringBuilder json = new StringBuilder("[");
        for (Task task : TASKS) {
            if (filter == null || filter.equals(task.status)) {
                if (json.length() > 1) {
                    json.append(',');
                }
                json.append(task.toJson());
            }
        }
        return json.append(']').toString();
    }

    /** Adds a task, open and at medium priority unless told otherwise, and returns it. */
    static synchronized String create(String title, String priority, String assignee) throws TaskBoardException {
        String name = blankToNull(title);
        if (name == null) {
            throw new TaskBoardException(400, "A task needs a title.");
        }
        String level = blankToNull(priority);
        if (level == null) {
            level = "medium";
        }
        requireOneOf("priority", level, PRIORITIES);
        // A blank assignee leaves the task unassigned, the same as omitting it: a caller that has
        // nobody in mind sends "" as readily as it leaves the argument out.
        Task task = new Task("T-" + nextId++, name, "open", level, blankToNull(assignee));
        TASKS.add(task);
        return task.toJson();
    }

    /** Moves a task to a status and returns it as it now stands. */
    static synchronized String setStatus(String id, String status) throws TaskBoardException {
        String target = blankToNull(status);
        if (target == null) {
            throw new TaskBoardException(400, "A status is required: one of " + STATUSES + ".");
        }
        requireOneOf("status", target, STATUSES);
        Task task = find(id);
        task.status = target;
        return task.toJson();
    }

    /** Gives a task to someone, replacing whoever holds it now, and returns it. */
    static synchronized String assign(String id, String assignee) throws TaskBoardException {
        String holder = blankToNull(assignee);
        if (holder == null) {
            throw new TaskBoardException(400, "An assignee needs a name.");
        }
        Task task = find(id);
        task.assignee = holder;
        return task.toJson();
    }

    private static Task find(String id) throws TaskBoardException {
        String key = blankToNull(id);
        for (Task task : TASKS) {
            if (task.id.equals(key)) {
                return task;
            }
        }
        throw new TaskBoardException(404, "No task with id " + id + " is on the board.");
    }

    private static void requireOneOf(String field, String value, List<String> allowed) throws TaskBoardException {
        if (!allowed.contains(value)) {
            throw new TaskBoardException(400, "Unknown " + field + " '" + value + "': use one of " + allowed + ".");
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** A unit of work on the board. */
    private static final class Task {
        final String id;
        final String title;
        String status;
        final String priority;
        String assignee;

        Task(String id, String title, String status, String priority, String assignee) {
            this.id = id;
            this.title = title;
            this.status = status;
            this.priority = priority;
            this.assignee = assignee;
        }

        String toJson() {
            return "{\"id\":" + Json.quote(id)
                    + ",\"title\":" + Json.quote(title)
                    + ",\"status\":" + Json.quote(status)
                    + ",\"priority\":" + Json.quote(priority)
                    + ",\"assignee\":" + (assignee == null ? "null" : Json.quote(assignee)) + "}";
        }
    }
}
