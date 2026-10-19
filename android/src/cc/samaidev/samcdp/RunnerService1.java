package cc.samaidev.samcdp;

/** Runner slot 1 — hosts one profile in process ":cdp1" (own cookie jar). */
public class RunnerService1 extends BaseRunnerService {
    @Override
    protected int slot() {
        return 1;
    }
}
