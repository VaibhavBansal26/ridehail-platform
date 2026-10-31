package com.ridehail.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
class NotificationConfig {

    /** The provider named in configuration; the logging stand-in when none is. (A test overrides it with a @Primary bean.) */
    @Bean
    PushNotifier pushNotifier(NotificationProperties props, ObjectMapper json) {
        return props.expo() ? new ExpoPushNotifier(props, json) : new LoggingNotifier();
    }

    /**
     * Sending goes through the provider over the network, so it never happens on the thread that books or completes a ride.
     * A bounded queue that refuses work when full (and the service just logs it) means a provider outage can slow nothing down.
     */
    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskExecutor notificationExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ridehail-notify-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
