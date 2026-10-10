/*
 * HDSL-web
 * Copyright (C) 2026  HDSL-web contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.web.task;

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.web.event.EventBus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/// Runs the blocking work behind the asynchronous API endpoints and tracks it.
///
/// Everything that would hold a request thread — an npm install, a launch, a
/// stop — is submitted here and reported through [EventBus] on the `tasks`
/// topic. Tasks are retained for the life of the server, so `/api/tasks` can
/// answer for work that finished long ago.
///
/// Concurrency is bounded where it matters: at most two installs run at once
/// (the two pnpm runs would otherwise fight over the shared store), and one
/// instance can only ever have one install in flight. Every other kind of task
/// runs on an unbounded virtual-thread pool, because a launch or a stop is
/// mostly waiting on a child process.
@NotNullByDefault
public final class TaskService {

    /// How a task is doing.
    public enum TaskState {
        RUNNING("running"),
        /// Parked on a human decision: the work reported that it needs an
        /// approval (a plugin's install scripts) and waits for
        /// `POST /api/tasks/{id}/approve`.
        WAITING_APPROVAL("waiting_approval"),
        DONE("done"),
        FAILED("failed");

        private final String wireName;

        TaskState(String wireName) {
            this.wireName = wireName;
        }

        /// The JSON spelling of the state.
        public String wireName() {
            return wireName;
        }
    }

    /// What a parked task is waiting for. `kind` names the sort of decision
    /// (only `build-scripts` exists), `keys` what it is about.
    public record ApprovalInfo(String kind, List<String> keys) {
    }

    /// The immutable view of a task, as `/api/tasks` reports it. `instanceId`
    /// is the instance the task belongs to, or null for global work — the
    /// panel attributes install progress by it, so it travels with every
    /// snapshot and not only with the `task` event. `approval` is
    /// present only while the state is `waiting_approval`; `result` only when
    /// the work attached one (a pack install's `instanceId`, an export's
    /// `filename`) — it is the machine-readable half of the done message.
    public record TaskInfo(String id, String kind, @Nullable String instanceId, String state, String message,
                           double fraction,
                           @Nullable String error, @Nullable ApprovalInfo approval,
                           @Nullable JsonObject result) {
    }

    /// What the submitter does with an approval decision, between the answer
    /// arriving and the work being retried: a plugin install writes the answer
    /// into the profile's `pnpm-workspace.yaml` here.
    @FunctionalInterface
    public interface ApprovalDecider {
        /// Applies the decision.
        ///
        /// @param allow whether the person allowed it
        /// @param keys  what the decision is about
        /// @throws Exception when the decision cannot be applied; the task fails
        void decide(boolean allow, List<String> keys) throws Exception;
    }

    /// The outcome of an approve call.
    public enum ApproveResult {
        /// The decision was delivered and the task runs again.
        OK,
        /// No task with that id.
        UNKNOWN_TASK,
        /// The task is not parked on an approval.
        NOT_WAITING,
    }

    /// A live task handle: the submitter updates the message and fraction as
    /// progress arrives, and the caller of [TaskService#cancel] marks it dead.
    public static final class Task {
        private static final java.util.concurrent.atomic.AtomicLong SEQUENCE =
                new java.util.concurrent.atomic.AtomicLong();

        private final String id;
        private final String kind;
        private final String instanceId;
        private final TaskService owner;
        private final long sequence = SEQUENCE.getAndIncrement();
        private volatile TaskState state = TaskState.RUNNING;
        private volatile String message = "";
        private volatile double fraction = -1;
        private volatile @Nullable String error;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        /// The worker thread executing this task; set when the run begins.
        /// Cancellation stops the process trees this thread started — no more.
        private volatile @Nullable Thread worker;
        /// The structured outcome the work attached, if any; published with
        /// every update once present, and part of the REST snapshot.
        private volatile @Nullable JsonObject result;
        /// What the task is parked on, and the future its decision arrives
        /// through. Both are null unless the state is WAITING_APPROVAL.
        private volatile @Nullable ApprovalInfo approval;
        private volatile @Nullable CompletableFuture<Boolean> approvalFuture;

        private Task(TaskService owner, String id, String kind, @Nullable String instanceId) {
            this.owner = owner;
            this.id = id;
            this.kind = kind;
            this.instanceId = instanceId;
        }

        /// Returns the task id.
        public String id() {
            return id;
        }

        /// Returns the current state.
        public TaskState state() {
            return state;
        }

        /// Returns the kind of work this task does.
        public String kind() {
            return kind;
        }

        /// Returns the instance the task belongs to, or `null` for global work.
        public @Nullable String instanceId() {
            return instanceId;
        }

        /// Reports whether a cancel was asked for.
        public boolean isCancelled() {
            return cancelled.get();
        }

        /// Updates the progress line and fraction, and publishes the change
        /// to the `tasks` topic — that is how install progress reaches the
        /// WebSocket clients.
        public void update(String newMessage, double newFraction) {
            this.message = newMessage;
            this.fraction = newFraction;
            owner.publish(this);
        }

        /// Attaches the structured outcome of the work — `{"instanceId": …}`
        /// of a pack install, `{"filename": …}` of an export — and publishes
        /// the change. The REST snapshot and every later task event carry it,
        /// so a client that only sees the done event still learns what the
        /// work produced.
        public void attachResult(JsonObject attached) {
            this.result = attached;
            owner.publish(this);
        }

        private void finish(TaskState newState, String finalMessage, @Nullable String finalError) {
            this.state = newState;
            this.message = finalMessage;
            this.error = finalError;
            if (newState == TaskState.DONE) {
                this.fraction = 1;
            }
        }

        /// Parks the task on an approval about `keys`.
        private void parkForApproval(List<String> keys) {
            this.approval = new ApprovalInfo("build-scripts", List.copyOf(keys));
            this.approvalFuture = new CompletableFuture<>();
            this.state = TaskState.WAITING_APPROVAL;
        }

        /// Blocks until a decision arrives or the wait is cancelled.
        private boolean awaitApprovalDecision() throws InterruptedException {
            CompletableFuture<Boolean> future = approvalFuture;
            if (future == null) {
                throw new InterruptedException("no approval pending");
            }
            try {
                return future.get();
            } catch (CancellationException | java.util.concurrent.ExecutionException e) {
                throw new InterruptedException("cancelled");
            }
        }

        /// Back to work after the decision.
        private void resumeFromApproval() {
            this.approval = null;
            this.state = TaskState.RUNNING;
        }

        private TaskInfo snapshot() {
            return new TaskInfo(id, kind, instanceId, state.wireName(), message, fraction, error, approval, result);
        }
    }

    /// Raised when the instance already has an install in flight.
    public static final class InstanceBusyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public InstanceBusyException(String message) {
            super(message);
        }
    }

    /// How many installs may run at once.
    private static final int MAX_INSTALLS = 2;

    /// The kinds that drive a package manager against one instance's profile:
    /// they are serialised per instance (two pnpm runs over one profile would
    /// race its manifest) and bounded globally by [MAX_INSTALLS] (two pnpm
    /// runs would otherwise fight over the shared store). `pack-install`
    /// belongs here although its instance does not exist yet at submit time:
    /// it runs the same pnpm installs, and the id it will create is already
    /// known, so the mutex keeps a second pack install (or a delete) from
    /// racing the first for the same id.
    private static final java.util.Set<String> INSTALL_LIKE =
            java.util.Set.of("install", "plugin-install", "plugin-remove", "plugin-local",
                    "pack-install");

    private static boolean installLike(@Nullable String kind) {
        return kind != null && INSTALL_LIKE.contains(kind);
    }

    private final EventBus bus;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore installSlots = new Semaphore(MAX_INSTALLS);
    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();
    /// Instance id → the install task currently working on it.
    private final ConcurrentHashMap<String, String> activeInstalls = new ConcurrentHashMap<>();

    public TaskService(EventBus bus) {
        this.bus = bus;
    }

    /// Submits work, returning its handle immediately.
    ///
    /// @param kind       the kind of work, reported in the task JSON
    /// @param instanceId the instance the task belongs to, or `null` for
    ///                   global work; install tasks are serialised per instance
    /// @param work       the work; its return value becomes the done message,
    ///                   a thrown exception becomes the failure error
    /// @return the live handle
    /// @throws InstanceBusyException when an install for the same instance is
    ///                               already running
    public Task submit(String kind, @Nullable String instanceId, Callable<String> work) {
        return submit(kind, instanceId, work, null);
    }

    /// Submits work that may park on a human decision, returning its handle
    /// immediately.
    ///
    /// When the work throws
    /// [org.jackhuang.hmcl.dsh.DshPluginInstaller.DshBuildScriptApprovalRequired],
    /// a task with a decider enters `waiting_approval`: it publishes the
    /// pending keys and parks until [#approve] delivers a decision, applies it
    /// through `approvalDecider`, and retries the work — `true` answers the
    /// pending entries with yes (the AUTO policy's semantics), `false` with no
    /// (NEVER's). A task without a decider fails on the exception instead.
    ///
    /// @param kind            the kind of work
    /// @param instanceId      the instance the task belongs to, or `null`
    /// @param work            the work, retried after each approval
    /// @param approvalDecider applies the decision, or `null` to fail instead
    /// @return the live handle
    /// @throws InstanceBusyException when an install for the same instance is
    ///                               already running
    public Task submit(String kind, @Nullable String instanceId, Callable<String> work,
                       @Nullable ApprovalDecider approvalDecider) {
        if (installLike(kind) && instanceId != null) {
            if (activeInstalls.putIfAbsent(instanceId, "pending") != null) {
                throw new InstanceBusyException("An install is already running for instance " + instanceId);
            }
        }
        Task task = new Task(this, UUID.randomUUID().toString(), kind, instanceId);
        tasks.put(task.id(), task);
        publish(task);
        executor.submit(() -> run(task, work, approvalDecider));
        return task;
    }

    /// Runs a task: acquires the install slot when needed, calls the work, and
    /// records the outcome.
    private void run(Task task, Callable<String> work, @Nullable ApprovalDecider approvalDecider) {
        task.worker = Thread.currentThread();
        boolean installBound = installLike(task.kind()) && task.instanceId() != null;
        try {
            if (installBound) {
                activeInstalls.put(task.instanceId(), task.id());
                if (task.isCancelled()) {
                    throw new InterruptedException("cancelled");
                }
                installSlots.acquire();
            }
            try {
                if (task.isCancelled()) {
                    throw new InterruptedException("cancelled");
                }
                String message = callWithApprovals(task, work, approvalDecider);
                task.finish(TaskState.DONE, message == null ? "" : message, null);
            } finally {
                if (installBound) {
                    installSlots.release();
                }
            }
        } catch (InterruptedException e) {
            task.finish(TaskState.FAILED, "cancelled", "cancelled");
        } catch (Exception e) {
            // A cancelled task usually dies because its process was destroyed —
            // report the cancellation, not a scary generic failure.
            if (task.isCancelled()) {
                task.finish(TaskState.FAILED, "cancelled", "cancelled");
            } else {
                task.finish(TaskState.FAILED, "failed", e.getMessage() == null ? e.toString() : e.getMessage());
            }
        } finally {
            if (installBound) {
                activeInstalls.remove(task.instanceId(), "pending");
                activeInstalls.remove(task.instanceId(), task.id());
            }
            publish(task);
        }
    }

    /// Calls the work, honouring the approval dance: a thrown
    /// `DshBuildScriptApprovalRequired` parks the task, the decision is
    /// applied, and the work runs again.
    private String callWithApprovals(Task task, Callable<String> work,
                                     @Nullable ApprovalDecider approvalDecider) throws Exception {
        while (true) {
            try {
                return work.call();
            } catch (org.jackhuang.hmcl.dsh.DshPluginInstaller.DshBuildScriptApprovalRequired required) {
                if (approvalDecider == null || task.isCancelled()) {
                    throw required;
                }
                task.parkForApproval(required.packages());
                publish(task);
                boolean allow = task.awaitApprovalDecision();
                task.resumeFromApproval();
                publish(task);
                approvalDecider.decide(allow, required.packages());
            }
        }
    }

    /// Cancels a running task, if it is one.
    ///
    /// Cancelling stops every child command the launcher is running
    /// ([org.jackhuang.hmcl.dsh.DshCommand#stopRunning]), which is the only way
    /// to reach the pnpm process behind an install. A task that has not started
    /// yet is marked and skips its work when it comes up; one parked on an
    /// approval is released from the wait.
    ///
    /// @param taskId the task id
    /// @return whether a task with that id exists
    public boolean cancel(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null || (task.state() != TaskState.RUNNING
                && task.state() != TaskState.WAITING_APPROVAL)) {
            return false;
        }
        task.cancelled.set(true);
        CompletableFuture<Boolean> future = task.approvalFuture;
        if (future != null) {
            future.completeExceptionally(new InterruptedException("cancelled"));
        }
        Thread worker = task.worker;
        if (worker != null) {
            org.jackhuang.hmcl.dsh.DshCommand.stopRunning(worker);
        }
        return true;
    }

    /// Delivers an approval decision to a parked task.
    ///
    /// The task returns to `running` and retries its work, with the decision
    /// applied by the submitter's decider first.
    ///
    /// @param taskId the task id
    /// @param allow  whether the pending scripts may run
    /// @return what happened
    public ApproveResult approve(String taskId, boolean allow) {
        Task task = tasks.get(taskId);
        if (task == null) {
            return ApproveResult.UNKNOWN_TASK;
        }
        CompletableFuture<Boolean> future = task.approvalFuture;
        if (task.state() != TaskState.WAITING_APPROVAL || future == null) {
            return ApproveResult.NOT_WAITING;
        }
        // Flip the visible state before releasing the parked thread, so a
        // client polling between the two never sees a decisionless wait.
        task.resumeFromApproval();
        publish(task);
        future.complete(allow);
        return ApproveResult.OK;
    }

    /// Returns a task snapshot.
    ///
    /// @param taskId the task id
    /// @return the snapshot, or empty when unknown
    public Optional<TaskInfo> get(String taskId) {
        Task task = tasks.get(taskId);
        return task == null ? Optional.empty() : Optional.of(task.snapshot());
    }

    /// Returns every known task, newest first.
    ///
    /// @return the snapshots
    public List<TaskInfo> list() {
        List<Task> all = new ArrayList<>(tasks.values());
        all.sort(Comparator.comparingLong((Task task) -> task.sequence).reversed());
        List<TaskInfo> result = new ArrayList<>(all.size());
        for (Task task : all) {
            result.add(task.snapshot());
        }
        return result;
    }

    /// Returns the install task currently working on an instance, if any.
    ///
    /// Only a version install answers here: the other install-like kinds
    /// (plugin work) share the per-instance mutex but are not what the
    /// instance page's install progress reports.
    ///
    /// @param instanceId the instance id
    /// @return the running install task
    public Optional<TaskInfo> activeInstall(String instanceId) {
        return activeInstallLike(instanceId).filter(info -> "install".equals(info.kind()));
    }

    /// Returns the install-like task currently working on an instance, of any
    /// install-like kind. The deletion guard asks this: an instance being
    /// package-managed must not disappear under pnpm.
    ///
    /// @param instanceId the instance id
    /// @return the running install-like task
    public Optional<TaskInfo> activeInstallLike(String instanceId) {
        String taskId = activeInstalls.get(instanceId);
        if (taskId == null || "pending".equals(taskId)) {
            return Optional.empty();
        }
        return get(taskId);
    }

    /// Returns the most recent finished install for an instance, if any.
    ///
    /// @param instanceId the instance id
    /// @return the last install task that is no longer running
    public Optional<TaskInfo> lastInstall(String instanceId) {
        Task newest = null;
        for (Task task : tasks.values()) {
            if (!"install".equals(task.kind()) || !instanceId.equals(task.instanceId())
                    || task.state() == TaskState.RUNNING) {
                continue;
            }
            if (newest == null || task.sequence > newest.sequence) {
                newest = task;
            }
        }
        return Optional.ofNullable(newest).map(Task::snapshot);
    }

    /// Runs work on the task pool without tracking it as a task.
    ///
    /// @param work the work
    public void execute(Runnable work) {
        executor.submit(work);
    }

    /// The executor behind this service, for callers that need to wait on it.
    public Executor executor() {
        return executor;
    }

    /// Publishes a task's current state to the `tasks` topic. The `instance`
    /// member is always present — the panel attributes progress by it — and is
    /// null only for global work. A parked task carries its `approval`, so a
    /// client that missed the REST poll still sees what is being asked.
    private void publish(Task task) {
        TaskInfo info = task.snapshot();
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "task");
        payload.addProperty("taskId", info.id());
        if (task.instanceId() != null) {
            payload.addProperty("instance", task.instanceId());
        } else {
            payload.add("instance", com.google.gson.JsonNull.INSTANCE);
        }
        payload.addProperty("message", info.message());
        payload.addProperty("fraction", info.fraction());
        payload.addProperty("state", info.state());
        if (info.error() != null) {
            payload.addProperty("error", info.error());
        }
        if (info.approval() != null) {
            JsonObject approval = new JsonObject();
            approval.addProperty("kind", info.approval().kind());
            com.google.gson.JsonArray keys = new com.google.gson.JsonArray();
            for (String key : info.approval().keys()) {
                keys.add(key);
            }
            approval.add("keys", keys);
            payload.add("approval", approval);
        }
        if (info.result() != null) {
            payload.add("result", info.result());
        }
        bus.publish("tasks", payload);
    }
}
