package com.hmdp.upgrade;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

/** A legacy process pointed at the cut-over catalog must not keep writing the retired order tables. */
@Component
public class CoreModeGate {
    private final JdbcTemplate db;
    @org.springframework.beans.factory.annotation.Value("${upgrade.core32.enabled:false}") private boolean core;
    public CoreModeGate(JdbcTemplate db){this.db=db;}
    public void legacy(){String mode=db.queryForObject("SELECT mode FROM ux_core_control WHERE id=1",String.class);if(!"LEGACY".equals(mode)&&!"MIGRATING".equals(mode))throw new Problem(503,"LEGACY_TRADING_DISABLED");}
    public void registration(){String mode=db.queryForObject("SELECT mode FROM ux_core_control WHERE id=1",String.class);if(core?!"CORE".equals(mode):!ListHolder.LEGACY.contains(mode))throw new Problem(503,"ACCOUNT_WRITES_PAUSED");}
    private static final class ListHolder{static final java.util.Set<String> LEGACY=java.util.Set.of("LEGACY","MIGRATING");}
}
