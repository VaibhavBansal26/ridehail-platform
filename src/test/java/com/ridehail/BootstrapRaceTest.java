package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehail.security.Role;
import com.ridehail.security.UserAccountRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * Several instances starting at once against a brand-new database all run the first-boot setup (platform ledger accounts,
 * the admin user). Provoke exactly that: undo the setup, then run it from many threads at the same instant.
 */
class BootstrapRaceTest extends ApiTestBase {

    @Autowired List<ApplicationRunner> runners;
    @Autowired UserAccountRepository accountRepo2;

    @Test
    void manyInstancesRunningFirstBootSetupAtTheSameTimeAllSucceedAndLeaveExactlyOneOfEverything() throws Exception {
        ApplicationArguments noArgs = new DefaultApplicationArguments();
        jdbc.update("delete from ledger_accounts where owner_id = '00000000-0000-0000-0000-000000000000'");
        jdbc.update("delete from user_accounts where phone = 'admin'");

        int instances = 8;
        var pool = Executors.newFixedThreadPool(instances);
        var go = new CountDownLatch(1);
        var results = new ArrayList<Future<Integer>>();
        for (int i = 0; i < instances; i++) {
            results.add(pool.submit((Callable<Integer>) () -> {
                go.await();
                for (ApplicationRunner runner : runners) runner.run(noArgs);    // what Spring Boot does at startup
                return 1;
            }));
        }
        go.countDown();
        for (Future<Integer> result : results) result.get();                   // none may throw
        pool.shutdown();

        assertThat(jdbc.queryForObject("select count(*) from ledger_accounts where owner_id = '00000000-0000-0000-0000-000000000000'", Integer.class))
                .isEqualTo(com.ridehail.ledger.AccountType.values().length - 2);   // each platform account exactly once (not the 2 per-rider kinds)
        assertThat(accountRepo2.findByPhone("admin")).isPresent();
        assertThat(accountRepo2.findByPhone("admin").orElseThrow().getRole()).isEqualTo(Role.ADMIN);
    }
}
