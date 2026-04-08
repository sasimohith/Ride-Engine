package com.ridesharing.shared.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Configures a custom thread pool for ALL @Async methods in the application.
 * We NEVER use the default Spring async executor because:
 *   - Default has no thread naming (impossible to debug)
 *   - Default has unlimited queue (can cause OutOfMemoryError)
 *   - Default has no rejection policy (tasks silently dropped)
 *
 * This custom pool provides:
 *   - Named threads ("async-pool-1", "async-pool-2") for easy log tracing
 *   - Bounded queue (500 tasks max) to prevent memory overflow
 *   - CallerRunsPolicy: if pool is full, the calling thread runs the task
 *     itself instead of dropping it — no work is ever lost
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    // Core pool size: 5 threads always alive, ready to pick up tasks instantly.
    // Sized for our expected async workload: notifications, matching, pricing.
    private static final int CORE_POOL_SIZE = 5;

    // Max pool size: can grow to 10 under heavy load (e.g., surge hour).
    // Beyond 10, tasks queue up instead of creating more threads.
    private static final int MAX_POOL_SIZE = 10;

    // Queue capacity: holds 500 waiting tasks before rejection kicks in.
    // If 10 threads are busy AND 500 tasks are queued, new tasks trigger rejection policy.
    private static final int QUEUE_CAPACITY = 500;

    // Thread name prefix: every thread from this pool will be named "async-pool-X".
    // When you see "async-pool-3" in logs, you know it came from this executor.
    private static final String THREAD_NAME_PREFIX = "async-pool-";

    /**
     * Creates and configures the thread pool executor bean.
     * This bean is automatically used by all @Async methods unless
     * a specific executor name is provided.
     *
     * @return configured Executor for async task execution
     */
    @Bean(name = "asyncExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);

        // CallerRunsPolicy: when pool AND queue are both full,
        // the thread that submitted the task runs it itself.
        // This provides backpressure — the caller slows down instead of losing tasks.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();

        log.info("Async executor initialized: core={}, max={}, queue={}",
                CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY);

        return executor;
    }

    /**
     * Handles uncaught exceptions from @Async methods.
     * Without this, async exceptions vanish silently — extremely dangerous.
     * This ensures every async failure is logged with full context.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) ->
                log.error("Async exception in method '{}': {}", method.getName(), throwable.getMessage(), throwable);
    }
}
