package cc.samaidev.samcdp;

/** Runner slot 2 — hosts one profile in process ":cdp2" (own cookie jar). */
public class RunnerService2 extends BaseRunnerService {
    @Override
    protected int slot() {
        return 2;
    }
}
